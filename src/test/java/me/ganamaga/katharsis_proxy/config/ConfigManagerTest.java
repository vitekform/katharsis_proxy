package me.ganamaga.katharsis_proxy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {

    private ConfigManager managerIn(Path directory) {
        return new ConfigManager(LoggerFactory.getLogger(ConfigManagerTest.class), directory);
    }

    @Test
    @DisplayName("writes the bundled default on first run, comments included")
    void writesDefaultConfig(@TempDir Path directory) throws ConfigurateException, IOException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        Path config = directory.resolve("config.yml");
        assertTrue(Files.exists(config));

        String written = Files.readString(config);
        assertTrue(written.contains("# Katharsis proxy configuration."), "header comment");
        assertTrue(written.contains("#   h2         - embedded file database"), "inline comments survive");
        assertTrue(written.contains("type: h2"));
    }

    @Test
    @DisplayName("defaults to the embedded H2 backend")
    void defaultsToH2(@TempDir Path directory) throws ConfigurateException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        DatabaseConfig database = manager.config().database();
        assertEquals(DatabaseType.H2, database.type());
        assertEquals("sa", database.username());
        assertEquals("", database.password());
        assertEquals("en_US", manager.config().defaultLanguage());
        assertTrue(database.jdbcUrl(directory).startsWith("jdbc:h2:file:"));
        assertTrue(database.jdbcUrl(directory).contains("MODE=PostgreSQL"),
                "H2 runs in PostgreSQL compatibility mode so one SQL dialect serves both");
    }

    @Test
    @DisplayName("a partial file overrides only what it names")
    void partialOverride(@TempDir Path directory) throws ConfigurateException, IOException {
        Files.writeString(directory.resolve("config.yml"), """
                database:
                  type: postgresql
                  postgresql:
                    host: db.example.com
                    port: 6543
                    database: katharsis_prod
                    username: kath
                    password: 'hunter2'
                    ssl-mode: require
                  pool:
                    maximum-pool-size: 25
                default-language: cs_CZ
                """);

        ConfigManager manager = managerIn(directory);
        manager.load();
        DatabaseConfig database = manager.config().database();

        assertEquals(DatabaseType.POSTGRESQL, database.type());
        assertEquals("jdbc:postgresql://db.example.com:6543/katharsis_prod?sslmode=require",
                database.jdbcUrl(directory));
        assertEquals("kath", database.username());
        assertEquals("hunter2", database.password());
        assertEquals("cs_CZ", manager.config().defaultLanguage());
        assertEquals(25, database.pool().maximumPoolSize(), "overridden");
        assertEquals(2, database.pool().minimumIdle(), "not named, so the default is kept");
        assertEquals("katharsis", database.h2().file(), "unused section keeps its default");
    }

    @Test
    @DisplayName("debug-log is on by default and can be turned off by name")
    void readsDebugLog(@TempDir Path directory) throws ConfigurateException, IOException {
        assertTrue(managerIn(directory).config().debugLog(), "on before anything is read");

        managerIn(directory).load();
        Files.writeString(directory.resolve("config.yml"), """
                debug-log: false
                """);
        ConfigManager manager = managerIn(directory);
        manager.load();

        assertFalse(manager.config().debugLog());
    }

    @Test
    @DisplayName("every motd key in the shipped config.yml maps onto a field")
    void shippedMotdKeysAllMap(@TempDir Path directory) throws ConfigurateException {
        managerIn(directory).load();

        // Configurate ignores keys it does not recognise, so a typo in the
        // shipped file would silently do nothing. Compare the keys the file
        // ships against the ones the mapper would write for the same object.
        CommentedConfigurationNode shipped = YamlConfigurationLoader.builder()
                .path(directory.resolve("config.yml"))
                .build()
                .load()
                .node("motd");

        CommentedConfigurationNode mapped = CommentedConfigurationNode.root();
        mapped.set(MotdConfig.class, new MotdConfig());

        assertEquals(mapped.childrenMap().keySet(), shipped.childrenMap().keySet());
        assertEquals(mapped.node("generator").childrenMap().keySet(),
                shipped.node("generator").childrenMap().keySet(), "nested generator section");
    }

    @Test
    @DisplayName("the discord bot is off by default and every shipped key maps onto a field")
    void discordDefaultsToOff(@TempDir Path directory) throws ConfigurateException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        DiscordConfig discord = manager.config().discord();
        assertFalse(discord.enabled());
        assertEquals("", discord.token());
        assertTrue(discord.chatChannels().isEmpty());

        CommentedConfigurationNode shipped = YamlConfigurationLoader.builder()
                .path(directory.resolve("config.yml"))
                .build()
                .load()
                .node("discord");
        CommentedConfigurationNode mapped = CommentedConfigurationNode.root();
        mapped.set(DiscordConfig.class, new DiscordConfig());
        assertEquals(mapped.childrenMap().keySet(), shipped.childrenMap().keySet());
    }

    @Test
    @DisplayName("chat channels map server ids to Discord channel ids")
    void discordChatChannels(@TempDir Path directory) throws ConfigurateException, IOException {
        Files.writeString(directory.resolve("config.yml"), """
                discord:
                  chat-channels:
                    lobby: 123456789012345678
                    lifesteal-eu: "234567890123456789"
                """);
        ConfigManager manager = managerIn(directory);
        manager.load();

        assertEquals(Map.of("lobby", 123456789012345678L, "lifesteal-eu", 234567890123456789L),
                manager.config().discord().chatChannels());
    }

    @Test
    @DisplayName("the motd section is off by default, so velocity.toml keeps the ping")
    void motdDefaultsToOff(@TempDir Path directory) throws ConfigurateException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        MotdConfig motd = manager.config().motd();
        assertFalse(motd.enabled());
        assertEquals(MotdMode.RANDOM, motd.mode());
        assertEquals(MotdFallback.CONFIG, motd.fallback());
        assertEquals(773, motd.minProtocol());
        assertEquals(774, motd.maxProtocol());
    }

    @Test
    @DisplayName("an existing file is never rewritten, so admin comments survive")
    void doesNotRewriteExistingFile(@TempDir Path directory) throws ConfigurateException, IOException {
        Path config = directory.resolve("config.yml");
        String original = """
                # my own note
                default-language: cs_CZ
                """;
        Files.writeString(config, original);

        managerIn(directory).load();

        assertEquals(original, Files.readString(config));
    }

    @Test
    @DisplayName("the shipped defaults leave moderation off, with the free tier's quota")
    void moderationDefaults(@TempDir Path directory) throws ConfigurateException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        ModerationConfig moderation = manager.config().moderation();
        assertFalse(moderation.enabled());
        assertTrue(moderation.words().isEmpty());
        assertTrue(moderation.patterns().isEmpty());
        assertTrue(moderation.allow().isEmpty());
        assertTrue(moderation.warnPlayer(), "the author is told by default");
        assertEquals(0L, moderation.staffChannelId());

        OpenAiConfig openai = moderation.openai();
        assertFalse(openai.enabled());
        assertEquals("", openai.apiKey());
        assertEquals("omni-moderation-latest", openai.model());
        assertEquals("https://api.openai.com/v1/moderations", openai.url());
        assertEquals(500, openai.requestsPerMinute());
        assertEquals(10_000, openai.tokensPerMinute());
        assertEquals(10_000, openai.requestsPerDay());
        assertEquals(0.0d, openai.scoreThreshold());
    }

    @Test
    @DisplayName("every moderation key is read under the name the shipped file documents")
    void readsModerationKeys(@TempDir Path directory) throws ConfigurateException, IOException {
        Files.writeString(directory.resolve("config.yml"), """
                moderation:
                  enabled: true
                  words:
                    - racist
                  patterns:
                    - 'discord\\.gg/\\w+'
                  allow:
                    - scunthorpe
                  warn-player: false
                  staff-channel-id: 123456789012345678
                  openai:
                    enabled: true
                    api-key: sk-test
                    model: omni-moderation-2024-09-26
                    url: https://example.invalid/v1/moderations
                    timeout-ms: 1500
                    requests-per-minute: 60
                    tokens-per-minute: 5000
                    requests-per-day: 900
                    categories:
                      - hate
                    score-threshold: 0.7
                """);
        ConfigManager manager = managerIn(directory);
        manager.load();

        ModerationConfig moderation = manager.config().moderation();
        assertTrue(moderation.enabled());
        assertEquals(java.util.List.of("racist"), moderation.words());
        assertEquals(java.util.List.of("discord\\.gg/\\w+"), moderation.patterns());
        assertEquals(java.util.List.of("scunthorpe"), moderation.allow());
        assertFalse(moderation.warnPlayer());
        assertEquals(123456789012345678L, moderation.staffChannelId());

        OpenAiConfig openai = moderation.openai();
        assertTrue(openai.enabled());
        assertEquals("sk-test", openai.apiKey());
        assertEquals("omni-moderation-2024-09-26", openai.model());
        assertEquals("https://example.invalid/v1/moderations", openai.url());
        assertEquals(1_500L, openai.timeoutMs());
        assertEquals(60, openai.requestsPerMinute());
        assertEquals(5_000, openai.tokensPerMinute());
        assertEquals(900, openai.requestsPerDay());
        assertEquals(java.util.List.of("hate"), openai.categories());
        assertEquals(0.7d, openai.scoreThreshold());
    }

    @Test
    @DisplayName("reloading picks up edits")
    void reloadPicksUpEdits(@TempDir Path directory) throws ConfigurateException, IOException {
        ConfigManager manager = managerIn(directory);
        manager.load();
        assertEquals("en_US", manager.config().defaultLanguage());

        Files.writeString(directory.resolve("config.yml"), "default-language: cs_CZ\n");
        manager.load();

        assertEquals("cs_CZ", manager.config().defaultLanguage());
    }

    @Test
    @DisplayName("the default punishments section loads its templates, escalation and automod settings")
    void defaultPunishments(@TempDir Path directory) throws ConfigurateException {
        ConfigManager manager = managerIn(directory);
        manager.load();

        PunishmentsConfig punishments = manager.config().punishments();
        assertTrue(punishments.enabled());
        assertEquals(PunishmentsConfig.DefaultScope.CURRENT_SERVER, punishments.defaultScope());
        assertTrue(punishments.banIpByDefault());
        assertTrue(punishments.mutedCommands().contains("msg"));
        assertEquals("30d", punishments.warns().expireAfter());
        assertEquals(2, punishments.warns().escalation().size());
        assertEquals(3, punishments.warns().escalation().get(0).count());
        assertEquals("mute", punishments.warns().escalation().get(0).type());
        assertEquals(java.util.List.of("7d", "30d", "perm"), punishments.templates().get("hacking").ladder());
        assertEquals("global", punishments.templates().get("hacking").scope());
        assertFalse(punishments.automod().enabled());
        assertEquals("AutoMod", punishments.automod().issuerName());
    }
}
