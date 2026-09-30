package me.ganamaga.katharsis_updater;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for {@code rsys.ganamaga.me/katharsis/public}: serves latest.json
 * the way the real one shapes it, and the file links as a redirect to a
 * "CDN" path, as the real one does.
 */
final class FakeReleaseSystem implements AutoCloseable {

    /** Bytes of each file, by name. */
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** Every request line, e.g. {@code GET /latest.json?prerelease=true&since=0.1}. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** What latest.json answers, by the prerelease parameter; missing means 404. */
    final Map<Boolean, String> latest = new ConcurrentHashMap<>();
    /** Overrides the sha256 advertised for a file, to test that a mismatch is caught. */
    final Map<String, String> advertisedSha = new ConcurrentHashMap<>();

    private final HttpServer server;

    FakeReleaseSystem() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/katharsis/public";
    }

    /** Publishes a release whose files hold their own names as content. */
    void publish(boolean prereleaseChannel, String version, boolean prerelease, String... filenames) {
        StringBuilder json = new StringBuilder();
        json.append("{\"project\":\"katharsis\",\"version\":\"").append(version)
                .append("\",\"title\":\"Katharsis ").append(version)
                .append("\",\"changelog\":\"notes\",\"prerelease\":").append(prerelease)
                .append(",\"url\":\"").append(baseUrl()).append("/latest\",\"files\":[");
        for (int i = 0; i < filenames.length; i++) {
            String name = filenames[i];
            byte[] content = ("jar " + name + " " + version).getBytes(StandardCharsets.UTF_8);
            files.put(name, content);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"filename\":\"").append(name).append("\",\"size\":").append(content.length)
                    .append(",\"contentType\":\"application/java-archive\",\"sha256\":\"")
                    .append(advertisedSha.getOrDefault(name, sha256(content)))
                    .append("\",\"primary\":").append(i == 0)
                    .append(",\"url\":\"").append(baseUrl()).append("/v/").append(version).append('/')
                    .append(name).append("\"}");
        }
        json.append("],\"releases\":[{\"version\":\"").append(version).append("\"},{\"version\":\"between\"}]}");
        latest.put(prereleaseChannel, json.toString());
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        try (exchange) {
            if (path.equals("/katharsis/public/latest.json")) {
                String body = latest.get(query != null && query.contains("prerelease=true"));
                if (body == null) {
                    send(exchange, 404, "{\"status\":404,\"message\":\"Nothing has been released yet\"}".getBytes(
                            StandardCharsets.UTF_8));
                } else {
                    send(exchange, 200, body.getBytes(StandardCharsets.UTF_8));
                }
            } else if (path.startsWith("/katharsis/public/v/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                exchange.getResponseHeaders().add("Location", "/cdn/" + name);
                exchange.sendResponseHeaders(302, -1);
            } else if (path.startsWith("/cdn/")) {
                byte[] content = files.get(path.substring("/cdn/".length()));
                if (content == null) {
                    send(exchange, 404, new byte[0]);
                } else {
                    send(exchange, 200, content);
                }
            } else {
                send(exchange, 404, new byte[0]);
            }
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
