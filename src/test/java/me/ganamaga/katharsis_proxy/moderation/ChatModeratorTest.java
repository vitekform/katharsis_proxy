package me.ganamaga.katharsis_proxy.moderation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layers in order, and the two failures that look alike and are treated as
 * opposites: out of quota lets a message through, anything else holds it back.
 */
class ChatModeratorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatModeratorTest.class);

    private static final String CLEAN = """
            {"results":[{"flagged":false,"categories":{"hate":false},"category_scores":{"hate":0.01}}]}""";

    private static final String FLAGGED = """
            {"results":[{"flagged":true,"categories":{"hate":true,"harassment":true},
              "category_scores":{"hate":0.99,"harassment":0.95}}]}""";

    private record Reply(int status, String body) {
    }

    private final Deque<Reply> replies = new ArrayDeque<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderations", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            Reply reply;
            synchronized (replies) {
                reply = replies.isEmpty() ? new Reply(200, CLEAN) : replies.removeFirst();
            }
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

    private ChatModerator moderator(RuleFilter rules, RateLimitBudget budget) {
        OpenAiModeration openAi = new OpenAiModeration(LOGGER, HttpClient.newHttpClient(), endpoint, "sk-test",
                "omni-moderation-latest", Duration.ofSeconds(5L), budget, List.of(), 0.0d);
        return new ChatModerator(LOGGER, rules, openAi);
    }

    private ChatModerator moderator(RuleFilter rules) {
        return moderator(rules, new RateLimitBudget(Clock.systemUTC(), 0, 0, 0));
    }

    private static RuleFilter words(String... terms) {
        return RuleFilter.compile(LOGGER, List.of(terms), List.of(), List.of());
    }

    @Test
    @DisplayName("a rule match costs no OpenAI request")
    void rulesComeFirst() {
        ModerationVerdict verdict = moderator(words("racist")).check("you are r a c i s t");

        assertEquals(ModerationSource.RULES, verdict.source());
        assertEquals("word: racist", verdict.reason());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("what the rules let through is sent to the model")
    void asksTheModel() {
        replies.add(new Reply(200, CLEAN));
        assertFalse(moderator(words("racist")).check("what a lovely evening").isFlagged());
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("reports what the model objected to")
    void readsTheModel() {
        replies.add(new Reply(200, FLAGGED));
        ModerationVerdict verdict = moderator(RuleFilter.empty()).check("something the rules do not know");

        assertEquals(ModerationSource.OPENAI, verdict.source());
        assertEquals("harassment, hate", verdict.reason());
        assertTrue(verdict.source().isViolation());
    }

    @Test
    @DisplayName("lets a message through when the day's quota is spent")
    void failsOpenOnAnExhaustedBudget() {
        RateLimitBudget budget = new RateLimitBudget(Clock.systemUTC(), 0, 0, 1);
        ChatModerator moderator = moderator(RuleFilter.empty(), budget);

        assertFalse(moderator.check("the one request there was room for").isFlagged());
        assertFalse(moderator.check("and one there was not").isFlagged());
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("lets a message through when OpenAI itself rate limits the proxy")
    void failsOpenOnRateLimiting() {
        replies.add(new Reply(429, "{\"error\":{\"message\":\"slow down\"}}"));
        assertFalse(moderator(RuleFilter.empty()).check("hello there").isFlagged());
    }

    @Test
    @DisplayName("holds a message back when the model cannot be reached or understood")
    void failsClosedOnAnError() {
        replies.add(new Reply(500, "upstream on fire"));
        ModerationVerdict verdict = moderator(RuleFilter.empty()).check("hello there");

        assertEquals(ModerationSource.UNAVAILABLE, verdict.source());
        // Nothing was decided about the player, only about the call.
        assertFalse(verdict.source().isViolation());

        replies.add(new Reply(200, "not json at all"));
        assertEquals(ModerationSource.UNAVAILABLE, moderator(RuleFilter.empty()).check("hello there").source());
    }

    @Test
    @DisplayName("still applies the rules with no model configured")
    void runsWithoutAModel() {
        ChatModerator moderator = new ChatModerator(LOGGER, words("racist"), null);

        assertFalse(moderator.usesModel());
        assertEquals(1, moderator.ruleCount());
        assertTrue(moderator.check("r4c1st").isFlagged());
        assertFalse(moderator.check("what a lovely evening").isFlagged());
        assertEquals(0, requests.get());
    }

    @Test
    @DisplayName("spends nothing on a message with no words in it")
    void skipsMessagesWithoutWords() {
        assertFalse(moderator(words("racist")).check("!!! ... ???").isFlagged());
        assertEquals(0, requests.get());
    }
}
