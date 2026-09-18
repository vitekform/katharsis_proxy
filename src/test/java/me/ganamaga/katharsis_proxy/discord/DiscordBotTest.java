package me.ganamaga.katharsis_proxy.discord;

import discord4j.gateway.intent.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordBotTest {

    private static void assertRejected(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a bot that was never started rejects tasks without touching Discord")
    void notStartedRejects() {
        DiscordBot bot = new DiscordBot(LoggerFactory.getLogger(DiscordBotTest.class));

        assertFalse(bot.isAccepting());
        assertRejected(bot.submit(client -> client.logout()));
        bot.stop();
    }

    @Test
    @DisplayName("a malformed token fails the login offline and rejects queued tasks")
    void malformedTokenRejects() {
        DiscordBot bot = new DiscordBot(LoggerFactory.getLogger(DiscordBotTest.class));
        try {
            // Discord4J reads the bot's id out of the token before any request,
            // so this fails without reaching the network.
            bot.start("not-a-token", true);
            assertRejected(bot.submit(client -> client.logout()));
            assertFalse(bot.isAccepting());
            assertThrows(IllegalStateException.class, () -> bot.start("not-a-token", true));
        } finally {
            bot.stop();
        }
        assertFalse(bot.isConnected());
    }

    @Test
    @DisplayName("Message Content is requested only when messages are read")
    void messageContentIntentOnlyWhenReading() {
        assertTrue(DiscordBot.intents(true).contains(Intent.MESSAGE_CONTENT));
        assertFalse(DiscordBot.intents(false).contains(Intent.MESSAGE_CONTENT));
        assertTrue(DiscordBot.intents(true).contains(Intent.GUILD_MESSAGES));
    }
}
