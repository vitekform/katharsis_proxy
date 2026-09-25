package me.ganamaga.katharsis_proxy.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import me.ganamaga.katharsis_proxy.config.PunishWebConfig;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.DatabaseTestSupport;
import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentWebSyncTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
    private static final IssuerRef STAFF = new IssuerRef(UUID.randomUUID(), "Moderator", IssuerRef.Source.GAME);
    private static final String TOKEN = "s3cret";

    private record Received(String token, JsonObject body) {
    }

    private final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private volatile int status = 200;
    private HttpServer server;
    private String url;

    private DatabaseManager database;
    private PunishmentRepository punishments;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PunishmentWebSync.PATH, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestHeaders().getFirst(PunishmentWebSync.TOKEN_HEADER),
                    JsonParser.parseString(body).getAsJsonObject()));
            byte[] reply = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        database = DatabaseTestSupport.started(directory);
        punishments = new PunishmentRepository(database);
        punishments.createTable();
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        server.stop(0);
        database.close();
    }

    private static PunishWebConfig config(String yaml) throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .build()
                .load()
                .get(PunishWebConfig.class, new PunishWebConfig());
    }

    private PunishWebConfig enabled(boolean includeSilent) throws ConfigurateException {
        return config("""
                enabled: true
                url: "%s"
                token: "%s"
                include-silent: %s
                """.formatted(url, TOKEN, includeSilent));
    }

    private PunishmentWebSync sync(PunishWebConfig web) {
        return new PunishmentWebSync(LoggerFactory.getLogger(PunishmentWebSyncTest.class),
                HttpClient.newHttpClient(), punishments, () -> web, executor);
    }

    private static Punishment punishment(UUID uuid, String ip, boolean silent) {
        return new Punishment(0, PunishmentType.BAN, uuid, uuid == null ? ip : "Steve", ip,
                PunishmentScope.server("lifesteal"), "Cheating", null, STAFF, silent, null,
                NOW, NOW.plus(Duration.ofDays(7)), null);
    }

    private Punishment stored(UUID uuid, String ip, boolean silent) throws Exception {
        return punishments.insert(punishment(uuid, ip, silent));
    }

    private Received next() throws InterruptedException {
        Received request = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "the website was never called");
        return request;
    }

    @Test
    @DisplayName("an issued punishment is pushed with the token and without the address")
    void pushesIssued() throws Exception {
        Punishment ban = stored(UUID.randomUUID(), "10.0.0.1", false);

        sync(enabled(false)).issued(ban);

        Received request = next();
        assertEquals(TOKEN, request.token());
        JsonArray published = request.body().getAsJsonArray("punishments");
        assertEquals(1, published.size());
        JsonObject json = published.get(0).getAsJsonObject();
        assertEquals(ban.id(), json.get("id").getAsLong());
        assertEquals("BAN", json.get("type").getAsString());
        assertEquals("Steve", json.get("playerName").getAsString());
        assertEquals("lifesteal", json.get("server").getAsString());
        assertEquals("Moderator", json.get("staffName").getAsString());
        assertEquals("2026-10-02T12:00:00Z", json.get("expiresAt").getAsString());
        assertFalse(request.body().toString().contains("10.0.0.1"), "the address must never be sent");
    }

    @Test
    @DisplayName("a lifted punishment is pushed with its revocation")
    void pushesRevoked() throws Exception {
        Punishment ban = stored(UUID.randomUUID(), null, false).withRevocation(
                new Punishment.Revocation(NOW.plusSeconds(60), IssuerRef.console(), "appeal won"));

        sync(enabled(false)).revoked(ban);

        JsonObject json = next().body().getAsJsonArray("punishments").get(0).getAsJsonObject();
        assertEquals("2026-09-25T12:01:00Z", json.get("revokedAt").getAsString());
        assertEquals("Console", json.get("revokedByName").getAsString());
        assertFalse(json.has("revokeReason"), "the reason for lifting stays with staff");
    }

    @Test
    @DisplayName("address-only and quiet punishments are sent as removed")
    void unpublishedAreRemoved() throws Exception {
        Punishment addressOnly = punishment(null, "10.0.0.2", false).withId(5);
        Punishment quiet = punishment(UUID.randomUUID(), null, true).withId(6);

        JsonObject body = PunishmentWebSync.payload(List.of(addressOnly, quiet), false);

        assertEquals(0, body.getAsJsonArray("punishments").size());
        assertEquals(List.of(5L, 6L), longs(body.getAsJsonArray("removed")));
        assertEquals(1, PunishmentWebSync.payload(List.of(quiet), true).getAsJsonArray("punishments").size());
    }

    @Test
    @DisplayName("a global punishment has no server")
    void globalHasNoServer() {
        Punishment global = new Punishment(3, PunishmentType.MUTE, UUID.randomUUID(), "Alex", null,
                PunishmentScope.global(), "Spam", null, STAFF, false, null, NOW, null, null);

        JsonObject json = PunishmentWebSync.toJson(global);

        assertTrue(json.get("server").isJsonNull());
        assertTrue(json.get("expiresAt").isJsonNull());
    }

    @Test
    @DisplayName("a full send walks the whole table in batches")
    void resyncSendsEverything() throws Exception {
        int total = PunishmentWebSync.BATCH + 5;
        for (int i = 0; i < total; i++) {
            stored(UUID.randomUUID(), null, false);
        }

        assertTrue(sync(enabled(false)).resync());

        List<Long> ids = new ArrayList<>();
        ids.addAll(ids(next()));
        ids.addAll(ids(next()));
        assertEquals(total, ids.size());
        assertEquals(total, ids.stream().distinct().count());
        assertNull(received.poll());
    }

    @Test
    @DisplayName("a full send stops at a batch the website refuses")
    void resyncStopsOnFailure() throws Exception {
        for (int i = 0; i < PunishmentWebSync.BATCH + 1; i++) {
            stored(UUID.randomUUID(), null, false);
        }
        status = 500;

        assertFalse(sync(enabled(false)).resync());

        next();
        assertNull(received.poll(), "nothing after the refused batch");
    }

    @Test
    @DisplayName("nothing is sent while turned off or without a token")
    void disabledSendsNothing() throws Exception {
        Punishment ban = stored(UUID.randomUUID(), null, false);

        assertFalse(sync(config("enabled: false\nurl: \"" + url + "\"\ntoken: x\n")).send(List.of(ban)));
        assertFalse(sync(config("enabled: true\nurl: \"" + url + "\"\n")).send(List.of(ban)));
        assertNull(received.poll());
    }

    @Test
    @DisplayName("the endpoint is added to the site address, with or without a trailing slash")
    void endpoint() {
        assertEquals("https://kathars.eu/api/kathars/integration/punishments",
                PunishmentWebSync.endpoint("https://kathars.eu").toString());
        assertEquals("https://kathars.eu/api/kathars/integration/punishments",
                PunishmentWebSync.endpoint("https://kathars.eu/").toString());
        assertNull(PunishmentWebSync.endpoint(""));
        assertNull(PunishmentWebSync.endpoint("kathars.eu"));
    }

    private static List<Long> ids(Received request) {
        List<Long> ids = new ArrayList<>();
        request.body().getAsJsonArray("punishments").forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsLong()));
        return ids;
    }

    private static List<Long> longs(JsonArray array) {
        List<Long> values = new ArrayList<>();
        array.forEach(e -> values.add(e.getAsLong()));
        return values;
    }
}
