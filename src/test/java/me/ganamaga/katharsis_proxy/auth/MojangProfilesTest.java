package me.ganamaga.katharsis_proxy.auth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangProfilesTest {

    private HttpServer server;
    private final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profiles/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/profiles/".length());
            asked.add(name);
            switch (name) {
                case "Notch" -> respond(exchange, 200, "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}");
                case "Empty" -> respond(exchange, 200, "{}");
                case "Gone" -> respond(exchange, 404, "{\"errorMessage\":\"Couldn't find any profile\"}");
                case "Throttled" -> respond(exchange, 429, "");
                default -> {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private MojangProfiles profiles() {
        return new MojangProfiles(LoggerFactory.getLogger(MojangProfilesTest.class),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/profiles", Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Mojang's answer is read as premium or not")
    void answers() {
        MojangProfiles profiles = profiles();

        assertEquals(MojangProfiles.Lookup.PREMIUM, profiles.lookup("Notch"));
        assertEquals(MojangProfiles.Lookup.NOT_PREMIUM, profiles.lookup("NobodyHere"));
        assertEquals(MojangProfiles.Lookup.NOT_PREMIUM, profiles.lookup("Gone"));
    }

    @Test
    @DisplayName("anything that says neither is unknown")
    void unknown() {
        MojangProfiles profiles = profiles();

        assertEquals(MojangProfiles.Lookup.UNKNOWN, profiles.lookup("Throttled"));
        assertEquals(MojangProfiles.Lookup.UNKNOWN, profiles.lookup("Empty"));
    }

    @Test
    @DisplayName("an unreachable Mojang is unknown, not an error")
    void unreachable() {
        int port = server.getAddress().getPort();
        server.stop(0);

        MojangProfiles profiles = new MojangProfiles(LoggerFactory.getLogger(MojangProfilesTest.class),
                "http://127.0.0.1:" + port + "/profiles/", Duration.ofSeconds(2));
        assertEquals(MojangProfiles.Lookup.UNKNOWN, profiles.lookup("Notch"));
    }

    @Test
    @DisplayName("a name Mojang could never have issued is not sent at all")
    void impossibleNamesAreNotSent() {
        MojangProfiles profiles = profiles();

        assertEquals(MojangProfiles.Lookup.NOT_PREMIUM, profiles.lookup("../../admin"));
        assertEquals(MojangProfiles.Lookup.NOT_PREMIUM, profiles.lookup("waytoolongforaminecraftname"));
        assertEquals(MojangProfiles.Lookup.NOT_PREMIUM, profiles.lookup("Štěpán"));
        assertTrue(asked.isEmpty(), () -> "asked about " + asked);
    }
}
