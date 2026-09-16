package me.ganamaga.katharsis_proxy.motd.generator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the uploader against a local HTTP server scripted with the responses
 * MineSkin gives.
 */
class MineSkinUploaderTest {

    /** A MineSkin-style texture value, with timestamp, profile and model metadata. */
    private static final String FULL_VALUE =
            "eyJ0aW1lc3RhbXAiOiAxNzU3ODgwMDAwMDAwLCAicHJvZmlsZUlkIjogImIwZDRiMjhiYzFkNzQ4ODlhZjBlODY2MWNlZTk2YWFjIiwg"
            + "InByb2ZpbGVOYW1lIjogIk1pbmVTa2luIiwgInNpZ25hdHVyZVJlcXVpcmVkIjogdHJ1ZSwgInRleHR1cmVzIjogeyJTS0lOIjogeyJ1"
            + "cmwiOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8zYjYwYTFmNmQ1NjJmNTJhYWViYmYxNDM0ZjFkZTE0Nzkz"
            + "M2EzYWZmZTBlNzY0ZmE0OWVhMDU3NTM2NjIzY2QzIiwgIm1ldGFkYXRhIjogeyJtb2RlbCI6ICJzbGltIn19fX0=";

    /** What the standalone script's strip_metadata() returns for {@link #FULL_VALUE}. */
    private static final String MINIFIED_VALUE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2I2MGExZjZkNTYy"
            + "ZjUyYWFlYmJmMTQzNGYxZGUxNDc5MzNhM2FmZmUwZTc2NGZhNDllYTA1NzUzNjYyM2NkMyJ9fX0=";

    private static final String SUCCESS = "{\"data\":{\"texture\":{\"value\":\"" + FULL_VALUE + "\"}}}";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};

    private record Reply(int status, String body, Map<String, String> headers) {
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }
    }

    private record Received(String authorization, String userAgent, String contentType, byte[] body) {
    }

    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate/upload", exchange -> {
            synchronized (received) {
                received.add(new Received(
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("User-Agent"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestBody().readAllBytes()));
            }
            Reply reply = replies.isEmpty() ? Reply.of(500, "{\"error\":\"unscripted\"}") : replies.poll();
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private MineSkinUploader uploader(String apiKey, int maxAttempts) {
        return new MineSkinUploader(LoggerFactory.getLogger(MineSkinUploaderTest.class),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate/upload"),
                apiKey, Duration.ZERO, Duration.ZERO, maxAttempts);
    }

    @Test
    @DisplayName("returns the texture value stripped down exactly as the script does")
    void returnsMinifiedTexture() throws Exception {
        replies.add(Reply.of(200, SUCCESS));

        assertEquals(MINIFIED_VALUE, uploader("", 1).upload(PNG));
    }

    @Test
    @DisplayName("sends the skin as a multipart file field named file")
    void sendsMultipartFile() throws Exception {
        replies.add(Reply.of(200, SUCCESS));

        uploader("", 1).upload(PNG);

        Received request = received.getFirst();
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
        String body = new String(request.body(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\""));
        assertTrue(body.contains("Content-Type: image/png\r\n\r\n"
                + new String(PNG, StandardCharsets.ISO_8859_1) + "\r\n--"));
        assertTrue(request.userAgent().contains("Katharsis"), "MineSkin rejects requests without a User-Agent");
    }

    @Test
    @DisplayName("sends no Authorization header without an API key")
    void anonymousByDefault() throws Exception {
        replies.add(Reply.of(200, SUCCESS));

        uploader("  ", 1).upload(PNG);

        assertNull(received.getFirst().authorization());
    }

    @Test
    @DisplayName("sends a configured API key as a bearer token")
    void sendsApiKey() throws Exception {
        replies.add(Reply.of(200, SUCCESS));

        uploader("secret-key", 1).upload(PNG);

        assertEquals("Bearer secret-key", received.getFirst().authorization());
    }

    @Test
    @DisplayName("a rate limit is waited out and retried")
    void retriesRateLimit() throws Exception {
        replies.add(new Reply(429, "{\"error\":\"Too many requests\"}", Map.of("Retry-After", "0")));
        replies.add(Reply.of(200, SUCCESS));

        assertEquals(MINIFIED_VALUE, uploader("", 3).upload(PNG));
        assertEquals(2, received.size());
    }

    @Test
    @DisplayName("server errors are retried until an attempt succeeds")
    void retriesServerErrors() throws Exception {
        replies.add(Reply.of(500, "{}"));
        replies.add(Reply.of(503, "{}"));
        replies.add(Reply.of(200, SUCCESS));

        assertEquals(MINIFIED_VALUE, uploader("", 3).upload(PNG));
        assertEquals(3, received.size());
    }

    @Test
    @DisplayName("gives up after the configured number of attempts")
    void givesUp() {
        replies.add(Reply.of(500, "{}"));
        replies.add(Reply.of(500, "{}"));
        replies.add(Reply.of(200, SUCCESS));

        IOException e = assertThrows(IOException.class, () -> uploader("", 2).upload(PNG));
        assertTrue(e.getMessage().contains("2 attempt"));
        assertEquals(2, received.size(), "never retries past the limit");
    }

    @Test
    @DisplayName("a rejected request is not retried, since it would fail the same way")
    void doesNotRetryClientErrors() {
        replies.add(Reply.of(401, "{\"error\":\"invalid api key\"}"));
        replies.add(Reply.of(200, SUCCESS));

        IOException e = assertThrows(IOException.class, () -> uploader("wrong", 5).upload(PNG));
        assertTrue(e.getMessage().contains("401"));
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("a 200 without a texture value is an error, not a blank head")
    void rejectsMalformedSuccess() {
        replies.add(Reply.of(200, "{\"data\":{}}"));

        assertThrows(IOException.class, () -> uploader("", 1).upload(PNG));
    }

    @Test
    @DisplayName("an unreachable server is retried, then reported")
    void unreachableServer() {
        server.stop(0);

        assertThrows(IOException.class, () -> uploader("", 2).upload(PNG));
    }

    @Test
    @DisplayName("a value that does not decode is kept rather than lost")
    void keepsUndecodableValues() {
        assertEquals("not-base64!", MineSkinUploader.minifyTexture("not-base64!"));
        assertEquals("e30=", MineSkinUploader.minifyTexture("e30="), "{} has no skin URL to keep");
    }
}
