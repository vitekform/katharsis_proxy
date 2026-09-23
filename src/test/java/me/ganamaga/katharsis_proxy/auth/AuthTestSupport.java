package me.ganamaga.katharsis_proxy.auth;

import me.ganamaga.katharsis_proxy.config.AuthConfig;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.AuthAccountRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An {@link AuthService} on a throwaway H2 database, with Mojang stubbed out. */
final class AuthTestSupport {

    static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    /** Cheap enough that a test can hash freely. */
    static final AuthConfig.Argon2Config CHEAP = new AuthConfig.Argon2Config(1024, 1, 1);

    /** Answers from a map; every name it does not know is not premium. */
    static final class StubMojang extends MojangProfiles {

        final Map<String, Lookup> answers = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        StubMojang() {
            super(LoggerFactory.getLogger(StubMojang.class), "http://127.0.0.1:9/", Duration.ofSeconds(1));
        }

        @Override
        public Lookup lookup(String username) {
            asked.add(username);
            return answers.getOrDefault(username, Lookup.NOT_PREMIUM);
        }
    }

    final DatabaseManager database;
    final AuthAccountRepository accounts;
    final StubMojang mojang = new StubMojang();
    final AuthService service;

    AuthTestSupport(Path directory, boolean premiumSkip) throws Exception {
        ConfigManager config = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        config.load();
        database = new DatabaseManager(LoggerFactory.getLogger("test"), directory);
        database.start(config.config().database());
        accounts = new AuthAccountRepository(database);
        accounts.createTable();
        service = new AuthService(LoggerFactory.getLogger("test"), database, accounts, new PasswordHasher(CHEAP),
                new PasswordPolicy(8, 64), mojang, premiumSkip, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    void close() {
        database.close();
    }
}
