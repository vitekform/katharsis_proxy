package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.DatabaseTestSupport;
import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.moderation.ModerationSource;
import me.ganamaga.katharsis_proxy.moderation.ModerationVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoWarnTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final ModerationVerdict SLUR = ModerationVerdict.flagged(ModerationSource.RULES, "word: x");

    private DatabaseManager database;
    private final List<Punishment> issued = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private AutoWarn autoWarn(Path directory, String yaml) throws Exception {
        ConfigManager config = TemplateRegistryTest.config(directory, yaml);
        PunishmentsConfig punishments = config.config().punishments();
        database = DatabaseTestSupport.started(directory, config.config().database());
        PunishmentRepository repository = new PunishmentRepository(database);
        repository.createTable();
        PunishmentService service = new PunishmentService(LoggerFactory.getLogger("test"), repository,
                new ActivePunishments(), Hierarchy.NONE, ip -> false, Clock.fixed(NOW, ZoneOffset.UTC));
        service.addObserver(new PunishmentObserver() {
            @Override
            public void issued(Punishment punishment) {
                issued.add(punishment);
            }
        });
        // Run the warning straight away, as the scheduler would later.
        return new AutoWarn(LoggerFactory.getLogger("test"), service,
                TemplateRegistry.load(punishments.templates(), warning -> { }), () -> punishments, Runnable::run,
                () -> List.of("lobby", "Lifesteal"));
    }

    private static ChatMessage message(UUID author, String server) {
        return new ChatMessage(author, "Kraxie", server, "x", NOW);
    }

    @Test
    @DisplayName("flagged chat earns a warning on its server, once per cooldown")
    void warnsOncePerCooldown(@TempDir Path directory) throws Exception {
        AutoWarn autoWarn = autoWarn(directory, """
                warns:
                  expire-after: 7d
                automod:
                  enabled: true
                  issuer-name: Guardian
                  cooldown: 5m
                """);
        UUID author = UUID.randomUUID();

        autoWarn.flagged(message(author, "lifesteal"), SLUR);
        autoWarn.flagged(message(author, "lifesteal"), SLUR);

        assertEquals(1, issued.size());
        Punishment warn = issued.get(0);
        assertEquals(PunishmentType.WARN, warn.type());
        assertEquals(new IssuerRef(null, "Guardian", IssuerRef.Source.AUTOMOD), warn.issuer());
        assertEquals(PunishmentScope.server("Lifesteal"), warn.scope());
        assertEquals("Chat: word: x", warn.reason());
        assertEquals(NOW.plus(Duration.ofDays(7)), warn.expiresAt());

        autoWarn.flagged(message(UUID.randomUUID(), "unknown"), SLUR);
        assertEquals(PunishmentScope.global(), issued.get(1).scope(), "an unknown server punishes everywhere");
    }

    @Test
    @DisplayName("nothing when off, or when the message could not be checked")
    void ignored(@TempDir Path directory) throws Exception {
        AutoWarn off = autoWarn(directory, "automod:\n  enabled: false\n");
        off.flagged(message(UUID.randomUUID(), "lobby"), SLUR);
        assertTrue(issued.isEmpty());
    }

    @Test
    @DisplayName("an unchecked message is not held against its author")
    void unavailable(@TempDir Path directory) throws Exception {
        AutoWarn autoWarn = autoWarn(directory, "automod:\n  enabled: true\n");
        autoWarn.flagged(message(UUID.randomUUID(), "lobby"),
                ModerationVerdict.flagged(ModerationSource.UNAVAILABLE, "timeout"));
        autoWarn.flagged(message(UUID.randomUUID(), "lobby"), ModerationVerdict.clean());
        assertTrue(issued.isEmpty());
    }

    @Test
    @DisplayName("a configured template is given instead, along its ladder")
    void template(@TempDir Path directory) throws Exception {
        AutoWarn autoWarn = autoWarn(directory, """
                automod:
                  enabled: true
                  cooldown: 0s
                  template: spam
                templates:
                  spam:
                    type: mute
                    reason: Spamming
                    ladder: [10m, 1h]
                """);
        UUID author = UUID.randomUUID();

        autoWarn.flagged(message(author, "lobby"), SLUR);
        autoWarn.flagged(message(author, "lobby"), SLUR);

        assertEquals(PunishmentType.MUTE, issued.get(0).type());
        assertEquals("Spamming (Chat: word: x)", issued.get(0).reason());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), issued.get(0).expiresAt());
        assertEquals(1, issued.size(), "the same mute on the same scope is not stacked");
    }
}
