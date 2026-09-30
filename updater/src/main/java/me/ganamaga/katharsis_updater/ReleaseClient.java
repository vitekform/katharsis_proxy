package me.ganamaga.katharsis_updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Talks to the release system's public API, {@code https://rsys.ganamaga.me/katharsis/public}:
 * {@code latest.json} for what is newest, and the file links for the jars.
 * Needs no account.
 */
public final class ReleaseClient {

    public static final String DEFAULT_URL = "https://rsys.ganamaga.me/katharsis/public";

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    /** Names a jar may be saved under; anything else could climb out of the folder. */
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar");

    private final HttpClient http;
    private final String baseUrl;
    private final String userAgent;

    /**
     * @param baseUrl   the project's public root, without a trailing slash
     * @param userAgent sent with every request, e.g. {@code Katharsis-Proxy/0.2}
     */
    public ReleaseClient(HttpClient http, String baseUrl, String userAgent) {
        this.http = http;
        this.baseUrl = stripSlash(baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl.strip());
        this.userAgent = userAgent;
    }

    public static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                // The file links answer with a redirect to the CDN.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * The newest release on the channel, or empty if nothing has been released
     * there yet.
     *
     * @param since the running version, so the history lists what was missed
     */
    public Optional<Release> latest(boolean prerelease, Version since) throws IOException, InterruptedException {
        String url = baseUrl + "/latest.json?prerelease=" + prerelease
                + "&since=" + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered HTTP " + response.statusCode());
        }
        if (looksLikeAPage(response.body())) {
            // A bot challenge or error page in front of rsys still answers 200.
            throw new IOException(url + " answered with a web page instead of JSON"
                    + (response.body().contains("not a bot") ? " (a bot challenge)" : ""));
        }
        return Optional.of(parseLatest(response.body()));
    }

    static Release parseLatest(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<ReleaseFile> files = new ArrayList<>();
            JsonArray fileArray = array(root, "files");
            for (JsonElement element : fileArray) {
                JsonObject file = element.getAsJsonObject();
                files.add(new ReleaseFile(string(file, "filename"), number(file, "size"),
                        string(file, "sha256").toLowerCase(Locale.ROOT), string(file, "url")));
            }
            List<String> history = new ArrayList<>();
            for (JsonElement element : array(root, "releases")) {
                history.add(string(element.getAsJsonObject(), "version"));
            }
            String version = string(root, "version");
            if (version.isEmpty()) {
                throw new IOException("latest.json names no version");
            }
            return new Release(Version.parse(version), string(root, "title"), string(root, "changelog"),
                    root.has("prerelease") && root.get("prerelease").getAsBoolean(),
                    List.copyOf(files), List.copyOf(history));
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new IOException("latest.json is not what was expected: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads {@code file} to {@code target}, checking its size and sha256
     * on the way. It is written beside the target first and moved into place
     * only once it checks out, so {@code target} is never a partial jar.
     *
     * @throws IOException if the download fails or the jar is not the one
     *                     the release system described
     */
    public void download(ReleaseFile file, Path target) throws IOException, InterruptedException {
        if (!isSafeFilename(file.filename())) {
            throw new IOException("refusing a file named '" + file.filename() + "'");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(file.url()))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", userAgent)
                .GET()
                .build();
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path part = parent.resolve(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException(file.url() + " answered HTTP " + response.statusCode());
                }
                MessageDigest sha256 = sha256();
                long written;
                try (OutputStream out = new DigestOutputStream(Files.newOutputStream(part), sha256)) {
                    written = in.transferTo(out);
                }
                if (file.size() > 0 && written != file.size()) {
                    throw new IOException("downloaded " + written + " bytes of " + file.filename()
                            + ", expected " + file.size());
                }
                String actual = HexFormat.of().formatHex(sha256.digest());
                if (!file.sha256().isEmpty() && !actual.equals(file.sha256())) {
                    throw new IOException(file.filename() + " has sha256 " + actual + ", expected " + file.sha256());
                }
            }
            JarFiles.moveReplacing(part, target);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    static boolean looksLikeAPage(String body) {
        String start = body.stripLeading();
        return start.startsWith("<");
    }

    static boolean isSafeFilename(String name) {
        return name != null && SAFE_FILENAME.matcher(name).matches();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JDK has SHA-256.
            throw new IllegalStateException(e);
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static long number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }

    private static String stripSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
