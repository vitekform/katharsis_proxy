package me.ganamaga.katharsis_proxy.moderation;

import com.sun.net.httpserver.HttpServer;
import me.ganamaga.katharsis_proxy.util.DebugLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the client against a local HTTP server scripted with OpenAI's answers. */
class OpenAiModerationTest {

    private static final String CLEAN = """
            {"id":"modr-1","model":"omni-moderation-latest","results":[{
              "flagged":false,
              "categories":{"hate":false,"harassment":false},
              "category_scores":{"hate":0.001,"harassment":0.002}
            }]}""";

    private static final String FLAGGED = """
            {"id":"modr-2","model":"omni-moderation-latest","results":[{
              "flagged":true,
              "categories":{"hate":true,"harassment":true,"violence":false},
              "category_scores":{"hate":0.98,"harassment":0.91,"violence":0.01}
            }]}""";

    /** The model let it pass, but one category scored high enough to argue about. */
    private static final String BORDERLINE = """
            {"id":"modr-3","model":"omni-moderation-latest","results":[{
              "flagged":false,
              "categories":{"hate":false,"harassment":false},
              "category_scores":{"hate":0.62,"harassment":0.10}
            }]}""";

    private record Reply(int status, String body, Map<String, String> headers) {

        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }
    }

    private record Received(String authorization, String contentType, String body) {
    }

    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = new ArrayList<>();
    private HttpServer server;
    private URI endpoint;

    /** Stands in for the plugin data directory, so debug.log is a real file. */
    @TempDir
    Path dataDirectory;

    private DebugLog debug;

    @BeforeEach
    void startServer() throws IOException {
        debug = DebugLog.create(LoggerFactory.getLogger(OpenAiModerationTest.class), dataDirectory, true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderations", exchange -> {
            synchronized (received) {
                received.add(new Received(
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            Reply reply;
            synchronized (replies) {
                reply = replies.isEmpty() ? Reply.of(200, CLEAN) : replies.removeFirst();
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/moderations");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiModeration client(RateLimitBudget budget, List<String> categories, double threshold) {
        return new OpenAiModeration(LoggerFactory.getLogger(OpenAiModerationTest.class), debug,
                HttpClient.newHttpClient(), endpoint, "sk-test", "omni-moderation-latest", Duration.ofSeconds(5L),
                budget, categories, threshold);
    }

    private OpenAiModeration client() {
        return client(new RateLimitBudget(Clock.systemUTC(), 0, 0, 0), List.of(), 0.0d);
    }

    @Test
    @DisplayName("sends the key, the model and the message as it was written")
    void sendsTheRequest() {
        client().check("Hello, World!");

        assertEquals(1, received.size());
        assertEquals("Bearer sk-test", received.get(0).authorization());
        assertEquals("application/json", received.get(0).contentType());
        assertTrue(received.get(0).body().contains("\"model\":\"omni-moderation-latest\""));
        assertTrue(received.get(0).body().contains("\"input\":\"Hello, World!\""));
    }

    @Test
    @DisplayName("reads a clean answer as clean")
    void readsClean() {
        replies.add(Reply.of(200, CLEAN));
        OpenAiModeration.Answer answer = client().check("hello");
        assertEquals(OpenAiModeration.Outcome.CLEAN, answer.outcome());
        assertTrue(answer.categories().isEmpty());
    }

    @Test
    @DisplayName("reports the categories the model objected to, in order")
    void readsFlagged() {
        replies.add(Reply.of(200, FLAGGED));
        OpenAiModeration.Answer answer = client().check("something awful");
        assertEquals(OpenAiModeration.Outcome.FLAGGED, answer.outcome());
        assertEquals(List.of("harassment", "hate"), answer.categories());
    }

    @Test
    @DisplayName("acts only on the categories the server asked about")
    void honoursTheCategoryList() {
        replies.add(Reply.of(200, FLAGGED));
        OpenAiModeration.Answer answer =
                client(new RateLimitBudget(Clock.systemUTC(), 0, 0, 0), List.of("hate"), 0.0d)
                        .check("something awful");
        assertEquals(OpenAiModeration.Outcome.FLAGGED, answer.outcome());
        assertEquals(List.of("hate"), answer.categories());

        replies.add(Reply.of(200, FLAGGED));
        OpenAiModeration.Answer ignored =
                client(new RateLimitBudget(Clock.systemUTC(), 0, 0, 0), List.of("violence"), 0.0d)
                        .check("something awful");
        assertEquals(OpenAiModeration.Outcome.CLEAN, ignored.outcome());
    }

    @Test
    @DisplayName("flags on a score the server set, even where the model did not")
    void honoursTheScoreThreshold() {
        replies.add(Reply.of(200, BORDERLINE));
        OpenAiModeration.Answer strict =
                client(new RateLimitBudget(Clock.systemUTC(), 0, 0, 0), List.of(), 0.5d).check("borderline");
        assertEquals(OpenAiModeration.Outcome.FLAGGED, strict.outcome());
        assertEquals(List.of("hate"), strict.categories());

        replies.add(Reply.of(200, BORDERLINE));
        assertEquals(OpenAiModeration.Outcome.CLEAN, client().check("borderline").outcome());
    }

    @Test
    @DisplayName("reports being rate limited, and waits as long as OpenAI asked")
    void readsRateLimiting() {
        replies.add(new Reply(429, "{\"error\":{\"message\":\"slow down\"}}", Map.of("Retry-After", "30")));
        RateLimitBudget budget = new RateLimitBudget(Clock.systemUTC(), 0, 0, 0);
        OpenAiModeration client = client(budget, List.of(), 0.0d);

        assertEquals(OpenAiModeration.Outcome.RATE_LIMITED, client.check("hello").outcome());
        assertTrue(budget.isExhausted());

        // Nothing more is sent while the pause holds.
        assertEquals(OpenAiModeration.Outcome.SKIPPED, client.check("hello again").outcome());
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("sends nothing once the budget is spent")
    void stopsAtTheBudget() {
        RateLimitBudget budget = new RateLimitBudget(Clock.systemUTC(), 0, 0, 1);
        OpenAiModeration client = client(budget, List.of(), 0.0d);

        assertEquals(OpenAiModeration.Outcome.CLEAN, client.check("one").outcome());
        assertEquals(OpenAiModeration.Outcome.SKIPPED, client.check("two").outcome());
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("reports an error status as an error, with the body abbreviated")
    void readsAnErrorStatus() {
        replies.add(Reply.of(401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}"));
        OpenAiModeration.Answer answer = client().check("hello");
        assertEquals(OpenAiModeration.Outcome.ERROR, answer.outcome());
        assertTrue(answer.detail().startsWith("HTTP 401"));
        assertTrue(answer.detail().contains("Incorrect API key"));
    }

    @Test
    @DisplayName("reports an answer it cannot read as an error rather than as clean")
    void readsAMalformedAnswer() {
        replies.add(Reply.of(200, "not json at all"));
        assertEquals(OpenAiModeration.Outcome.ERROR, client().check("hello").outcome());

        replies.add(Reply.of(200, "{\"results\":[]}"));
        assertEquals(OpenAiModeration.Outcome.ERROR, client().check("hello").outcome());
    }

    @Test
    @DisplayName("records the request and the answer in debug.log, and never the key")
    void writesTheCallToTheDebugLog() throws IOException {
        replies.add(Reply.of(200, FLAGGED));
        client().check("Hello, World!");

        String log = debugLog();
        List<String> calls = log.lines().filter(line -> line.contains("openai call=1 ")).toList();
        assertEquals(2, calls.size(), log);
        assertTrue(calls.get(0).contains("request model=omni-moderation-latest"), calls.get(0));
        assertTrue(calls.get(0).contains("url=" + endpoint), calls.get(0));
        assertTrue(calls.get(0).contains("text=\"Hello, World!\""), calls.get(0));
        assertTrue(calls.get(1).contains("answer status=200"), calls.get(1));
        assertTrue(calls.get(1).contains("outcome=FLAGGED"), calls.get(1));
        assertTrue(calls.get(1).contains("categories=[harassment, hate]"), calls.get(1));
        assertFalse(log.contains("sk-test"), "the API key must never reach debug.log: " + log);
    }

    @Test
    @DisplayName("records an error status in debug.log with the body")
    void writesAFailedCallToTheDebugLog() throws IOException {
        replies.add(Reply.of(401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}"));
        client().check("Hello");

        String log = debugLog();
        assertTrue(log.contains("answer status=401"), log);
        assertTrue(log.contains("outcome=ERROR"), log);
        assertTrue(log.contains("Incorrect API key provided"), log);
    }

    @Test
    @DisplayName("records a call it did not make because the budget is spent")
    void writesASkippedCallToTheDebugLog() throws IOException {
        OpenAiModeration client = client(new RateLimitBudget(Clock.systemUTC(), 0, 0, 1), List.of(), 0.0d);
        client.check("first");
        client.check("second");

        String log = debugLog();
        assertTrue(log.contains("openai call=2 skipped"), log);
        assertTrue(log.contains("reason=out-of-quota"), log);
    }

    @Test
    @DisplayName("writes nothing at all when debug logging is off")
    void writesNothingWhenDisabled() throws IOException {
        // The file the @BeforeEach log opened. A disabled log must not bring it back.
        Files.delete(dataDirectory.resolve(DebugLog.FILE_NAME));
        debug = DebugLog.DISABLED;
        client().check("Hello");

        assertFalse(Files.exists(dataDirectory.resolve(DebugLog.FILE_NAME)));
    }

    /** Every line written for this test, newlines and all. */
    private String debugLog() throws IOException {
        return Files.readString(dataDirectory.resolve(DebugLog.FILE_NAME), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("charges longer messages more tokens")
    void estimatesTokens() {
        assertTrue(OpenAiModeration.estimateTokens("a longer message here")
                > OpenAiModeration.estimateTokens("short"));
        assertFalse(OpenAiModeration.estimateTokens("") == 0);
    }
}
