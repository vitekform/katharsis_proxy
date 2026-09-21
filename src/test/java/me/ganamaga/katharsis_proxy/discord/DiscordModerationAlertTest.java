package me.ganamaga.katharsis_proxy.discord;

import discord4j.common.util.Snowflake;
import discord4j.discordjson.json.EmbedData;
import discord4j.discordjson.json.EmbedFieldData;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.ModerationConfig;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.moderation.ModerationSource;
import me.ganamaga.katharsis_proxy.moderation.ModerationVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordModerationAlertTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordModerationAlertTest.class);
    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");
    private static final Instant SENT = Instant.parse("2026-09-21T12:00:00Z");

    private static ChatMessage message(String content) {
        return new ChatMessage(PLAYER, "Notch", "lifesteal", content, SENT);
    }

    private static String field(EmbedData embed, String name) {
        return embed.fields().toOptional().orElse(List.of()).stream()
                .filter(field -> field.name().equals(name))
                .map(EmbedFieldData::value)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("the embed carries the player, the server, what caught it and the message")
    void buildsTheEmbed() {
        EmbedData embed = DiscordModerationAlert.embed(message("you are racist"),
                ModerationVerdict.flagged(ModerationSource.RULES, "word: racist"));

        assertEquals("Chat message held back", embed.title().toOptional().orElseThrow());
        assertEquals("Notch (" + PLAYER + ")", field(embed, "Player"));
        assertEquals("lifesteal", field(embed, "Server"));
        assertEquals("rules — word: racist", field(embed, "Caught by"));
        assertEquals("you are racist", field(embed, "Message"));
        assertEquals("2026-09-21T12:00:00Z", embed.timestamp().toOptional().orElseThrow());
    }

    @Test
    @DisplayName("a verdict with nothing to add names only what caught it")
    void describesASourceWithoutAReason() {
        EmbedData embed = DiscordModerationAlert.embed(message("hi"),
                ModerationVerdict.flagged(ModerationSource.OPENAI, ""));

        assertEquals("openai", field(embed, "Caught by"));
    }

    @Test
    @DisplayName("Discord formatting in a held-back message shows up literally")
    void escapesTheMessage() {
        EmbedData embed = DiscordModerationAlert.embed(message("*not bold* ||spoiler|| <@123>"),
                ModerationVerdict.flagged(ModerationSource.RULES, "word: x"));

        assertEquals("\\*not bold\\* \\|\\|spoiler\\|\\| \\<@123>", field(embed, "Message"));
    }

    @Test
    @DisplayName("an empty message still makes a valid field")
    void handlesAnEmptyMessage() {
        EmbedData embed = DiscordModerationAlert.embed(message(""),
                ModerationVerdict.flagged(ModerationSource.RULES, "word: x"));

        assertEquals("—", field(embed, "Message"));
    }

    @Test
    @DisplayName("a long message is cut to Discord's limit, without a dangling escape")
    void cutsALongMessage() {
        EmbedData embed = DiscordModerationAlert.embed(message("*".repeat(5_000)),
                ModerationVerdict.flagged(ModerationSource.RULES, "word: x"));

        String value = field(embed, "Message");
        assertTrue(value.length() <= DiscordModerationAlert.FIELD_MAX_LENGTH);
        assertTrue(value.endsWith("\\*…"), "the cut must not leave a backslash escaping the ellipsis");
    }

    @Test
    @DisplayName("only a configured channel id is reported to")
    void resolvesTheChannel(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                moderation:
                  staff-channel-id: 123456789012345678
                """);
        ConfigManager config = new ConfigManager(LOGGER, directory);
        config.load();

        assertEquals(Optional.of(Snowflake.of(123456789012345678L)),
                new DiscordModerationAlert(LOGGER, new DiscordBot(LOGGER), () -> config.config().moderation())
                        .channel());
        assertEquals(Optional.empty(),
                new DiscordModerationAlert(LOGGER, new DiscordBot(LOGGER), ModerationConfig::new).channel());
    }

    @Test
    @DisplayName("a message nothing could check is not reported, since it says nothing about the player")
    void doesNotReportTheUnavailable() {
        DiscordModerationAlert alert =
                new DiscordModerationAlert(LOGGER, new DiscordBot(LOGGER), ModerationConfig::new);

        assertDoesNotThrow(() -> alert.flagged(message("hi"),
                ModerationVerdict.flagged(ModerationSource.UNAVAILABLE, "timeout")));
        assertDoesNotThrow(() -> alert.flagged(message("hi"), ModerationVerdict.clean()));
        // And with the bot off and no channel, a real finding is harmless too.
        assertDoesNotThrow(() -> alert.flagged(message("hi"),
                ModerationVerdict.flagged(ModerationSource.RULES, "word: x")));
    }
}
