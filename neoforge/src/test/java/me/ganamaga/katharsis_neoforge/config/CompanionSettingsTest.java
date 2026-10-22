package me.ganamaga.katharsis_neoforge.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSettingsTest {

    @TempDir
    Path config;

    @Test
    @DisplayName("the first start writes the documented defaults and reads them back")
    void writesDefaults() throws IOException {
        Path file = config.resolve("sub").resolve(CompanionSettings.FILE_NAME);

        CompanionSettings settings = CompanionSettings.load(file);

        assertTrue(Files.readString(file).contains("discord.chat-channels"), "comments are kept");
        assertEquals("", settings.serverId());
        assertEquals(5L, settings.requestTimeoutSeconds());
        assertEquals("<red>Only players can use this command.", settings.playersOnly());
    }

    @Test
    @DisplayName("an admin's own values are read as UTF-8")
    void readsEdits() throws IOException {
        Path file = config.resolve(CompanionSettings.FILE_NAME);
        Files.writeString(file, "server-id = modded \nrequest-timeout-seconds=12\n"
                + "proxy-unreachable=<red>Proxy nedostupná\n", StandardCharsets.UTF_8);

        CompanionSettings settings = CompanionSettings.load(file);

        assertEquals("modded", settings.serverId());
        assertEquals(12L, settings.requestTimeoutSeconds());
        assertEquals("<red>Proxy nedostupná", settings.proxyUnreachable());
    }

    @Test
    @DisplayName("nonsense and blanks fall back to the defaults")
    void fallbacks() throws IOException {
        Path file = config.resolve(CompanionSettings.FILE_NAME);
        Files.writeString(file, "request-timeout-seconds=soon\nplayers-only=\n");

        CompanionSettings settings = CompanionSettings.load(file);

        assertEquals(5L, settings.requestTimeoutSeconds());
        assertEquals("<red>Only players can use this command.", settings.playersOnly());
    }

    @Test
    @DisplayName("network-fingerprint is replaced where it stands, every other line kept")
    void writesNetworkFingerprint() throws IOException {
        Path file = config.resolve(CompanionSettings.FILE_NAME);
        Files.writeString(file, "# comment\nnetwork-fingerprint-old=1\nnetwork-fingerprint = GN1:OLD\nserver-id=x\n");

        CompanionSettings.writeNetworkFingerprint(file, "GN1:NEW");

        assertEquals("# comment\nnetwork-fingerprint-old=1\nnetwork-fingerprint=GN1:NEW\nserver-id=x\n",
                Files.readString(file));
        assertEquals("GN1:NEW", CompanionSettings.load(file).networkFingerprint());
    }

    @Test
    @DisplayName("network-fingerprint is added to a file from before it existed")
    void addsNetworkFingerprint() throws IOException {
        Path file = config.resolve(CompanionSettings.FILE_NAME);
        Files.writeString(file, "server-id=x\n");

        CompanionSettings.writeNetworkFingerprint(file, "GN1:NEW");

        assertEquals("server-id=x\nnetwork-fingerprint=GN1:NEW\n", Files.readString(file));
    }
}
