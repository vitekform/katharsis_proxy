package me.ganamaga.katharsis_proxy.motd.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.ganamaga.katharsis_proxy.config.MotdGeneratorConfig;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Uploads skins to MineSkin, which signs them through a real Minecraft account
 * and hands back a texture value any client will render.
 *
 * <p>MineSkin rate limits uploads, so successful uploads are spaced out and
 * rate-limited or failed ones are retried after a pause. Unlike the standalone
 * script this gives up after a bounded number of attempts, and does not retry
 * errors that cannot succeed on a second try, such as a rejected API key.</p>
 */
public final class MineSkinUploader implements TextureUploader {

    private static final String USER_AGENT = "Katharsis-Proxy-BannerGenerator";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Logger logger;
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration uploadDelay;
    private final Duration retryDelay;
    private final int maxAttempts;

    /** When the next upload may start. Guarded by {@code this}. */
    private long nextUploadAtNanos = Long.MIN_VALUE;

    public MineSkinUploader(Logger logger, URI endpoint, String apiKey,
                            Duration uploadDelay, Duration retryDelay, int maxAttempts) {
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.uploadDelay = nonNegative(uploadDelay);
        this.retryDelay = nonNegative(retryDelay);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * @throws IllegalArgumentException if the configured URL is not a valid URI
     */
    public static MineSkinUploader fromConfig(Logger logger, MotdGeneratorConfig config) {
        return new MineSkinUploader(logger, URI.create(config.mineskinUrl()), config.mineskinApiKey(),
                Duration.ofMillis(config.uploadDelayMs()), Duration.ofMillis(config.retryDelayMs()),
                config.maxAttempts());
    }

    @Override
    public synchronized String upload(byte[] skinPng) throws IOException, InterruptedException {
        waitForUploadSlot();

        String lastProblem = "no attempt was made";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration pause = retryDelay;
            HttpResponse<String> response = null;
            try {
                response = http.send(request(skinPng), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                lastProblem = e.toString();
            }

            if (response != null) {
                int status = response.statusCode();
                if (status == 200) {
                    nextUploadAtNanos = System.nanoTime() + uploadDelay.toNanos();
                    return minifyTexture(textureValue(response.body()));
                }
                lastProblem = "HTTP " + status + ": " + abbreviate(response.body());
                if (status == 429) {
                    pause = retryAfter(response).orElse(retryDelay);
                } else if (status < 500) {
                    // A bad image or a rejected key fails the same way every time.
                    throw new IOException("MineSkin rejected the upload with " + lastProblem);
                }
            }

            if (attempt < maxAttempts) {
                logger.warn("MineSkin upload attempt {}/{} failed ({}); retrying in {}s.",
                        attempt, maxAttempts, lastProblem, pause.toSeconds());
                Thread.sleep(pause);
            }
        }
        throw new IOException("MineSkin upload failed after " + maxAttempts + " attempt(s): " + lastProblem);
    }

    private void waitForUploadSlot() throws InterruptedException {
        long waitNanos = nextUploadAtNanos - System.nanoTime();
        if (nextUploadAtNanos != Long.MIN_VALUE && waitNanos > 0) {
            Thread.sleep(Duration.ofNanos(waitNanos));
        }
    }

    private HttpRequest request(byte[] skinPng) {
        String boundary = "----KatharsisBanner" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, skinPng)));
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static byte[] multipart(String boundary, byte[] skinPng) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(skinPng.length + 256);
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(skinPng);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    /** Reads {@code data.texture.value} out of a successful v1 response. */
    private static String textureValue(String body) throws IOException {
        try {
            return JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("data")
                    .getAsJsonObject("texture")
                    .get("value").getAsString();
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IOException("MineSkin answered 200 without a texture value: " + abbreviate(body), e);
        }
    }

    /**
     * Cuts a texture value down to the skin URL, the only part a client needs.
     *
     * <p>MineSkin's value also carries a timestamp, profile id and name. Every
     * head in a banner repeats it, and the whole banner has to fit in one status
     * packet, so the rest is dropped. A value that does not decode is returned
     * unchanged rather than lost.</p>
     */
    static String minifyTexture(String value) {
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            String url = JsonParser.parseString(decoded).getAsJsonObject()
                    .getAsJsonObject("textures")
                    .getAsJsonObject("SKIN")
                    .get("url").getAsString();

            JsonObject skin = new JsonObject();
            skin.addProperty("url", url);
            JsonObject textures = new JsonObject();
            textures.add("SKIN", skin);
            JsonObject minified = new JsonObject();
            minified.add("textures", textures);

            return Base64.getEncoder().encodeToString(GSON.toJson(minified).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return value;
        }
    }

    /** Seconds from a {@code Retry-After} header. The HTTP-date form is not used by MineSkin. */
    private static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(header -> {
            try {
                return Optional.of(Duration.ofSeconds(Math.max(0L, Long.parseLong(header.strip()))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private static Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static String abbreviate(String body) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}
