package me.ganamaga.katharsis_proxy.discord;

import discord4j.common.util.Snowflake;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordChatRelayTest {

    @Test
    @DisplayName("formats a message as **player:** text")
    void formats() {
        assertEquals("**Notch:** hello world", DiscordChatRelay.format("Notch", "hello world"));
        assertEquals("**Notch:** ", DiscordChatRelay.format("Notch", ""));
    }

    @Test
    @DisplayName("Discord formatting in names and messages shows up literally")
    void escapesMarkdown() {
        assertEquals("**Cool\\_Guy\\_:** \\*not bold\\* \\~\\~x\\~\\~ \\`code\\` \\|\\|spoiler\\|\\|",
                DiscordChatRelay.format("Cool_Guy_", "*not bold* ~~x~~ `code` ||spoiler||"));
        assertEquals("**Notch:** \\[click\\](https://example.com) \\<@123> back\\\\slash",
                DiscordChatRelay.format("Notch", "[click](https://example.com) <@123> back\\slash"));
    }

    @Test
    @DisplayName("line breaks become spaces, so a message cannot start a heading or quote")
    void removesLineBreaks() {
        assertEquals("**Notch:** hi # heading > quote", DiscordChatRelay.format("Notch", "hi\n# heading\r\n> quote"));
    }

    @Test
    @DisplayName("an overlong message is cut to Discord's limit")
    void truncates() {
        String formatted = DiscordChatRelay.format("Notch", "a".repeat(5_000));

        assertEquals(DiscordChatRelay.MESSAGE_MAX_LENGTH, formatted.length());
        assertTrue(formatted.startsWith("**Notch:** aaa"));
        assertTrue(formatted.endsWith("a…"));
    }

    @Test
    @DisplayName("a cut never splits an emoji in half")
    void truncatesOnCodePoints() {
        String formatted = DiscordChatRelay.format("Notch", "👋".repeat(2_000));

        assertTrue(formatted.length() <= DiscordChatRelay.MESSAGE_MAX_LENGTH);
        assertTrue(formatted.endsWith("👋…"));
    }

    @Test
    @DisplayName("only server ids in chat-channels have a channel, so chat from any other server is not posted")
    void unmappedServersAreNotPosted(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                discord:
                  chat-channels:
                    lobby: 123456789012345678
                    broken: 0
                """);
        ConfigManager config = new ConfigManager(LoggerFactory.getLogger(DiscordChatRelayTest.class), directory);
        config.load();
        DiscordChatRelay relay = new DiscordChatRelay(LoggerFactory.getLogger(DiscordChatRelayTest.class),
                new DiscordBot(LoggerFactory.getLogger(DiscordChatRelayTest.class)), () -> config.config().discord());

        assertEquals(Optional.of(Snowflake.of(123456789012345678L)), relay.channelFor("lobby"));
        assertEquals(Optional.empty(), relay.channelFor("lifesteal"), "not in the map");
        assertEquals(Optional.empty(), relay.channelFor("Lobby"), "ids match exactly");
        assertEquals(Optional.empty(), relay.channelFor("broken"), "not a channel id");
    }

    @Test
    @DisplayName("relaying with the bot off or no channel configured does nothing")
    void relayWithoutBotIsHarmless() {
        DiscordChatRelay relay = new DiscordChatRelay(LoggerFactory.getLogger(DiscordChatRelayTest.class),
                new DiscordBot(LoggerFactory.getLogger(DiscordChatRelayTest.class)), DiscordConfig::new);
        ChatMessage message = new ChatMessage(UUID.randomUUID(), "Notch", "lobby", "hi", Instant.now());

        assertDoesNotThrow(() -> relay.relay("lobby", message));
    }
}
