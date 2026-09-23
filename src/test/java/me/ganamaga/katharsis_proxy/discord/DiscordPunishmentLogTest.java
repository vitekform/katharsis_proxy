package me.ganamaga.katharsis_proxy.discord;

import discord4j.discordjson.json.EmbedData;
import discord4j.discordjson.json.EmbedFieldData;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordPunishmentLogTest {

    private static final UUID PLAYER = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private static Punishment ban(String ip, Instant expiresAt) {
        return new Punishment(7, PunishmentType.BAN, PLAYER, "Not_ch", ip, PunishmentScope.group("survival"),
                "x-ray @everyone", "hacking", new IssuerRef(UUID.randomUUID(), "Mod", IssuerRef.Source.DISCORD),
                false, 3L, NOW, expiresAt, null);
    }

    private static Optional<String> field(EmbedData embed, String name) {
        return embed.fields().toOptional().orElse(List.of()).stream()
                .filter(field -> field.name().equals(name))
                .map(EmbedFieldData::value)
                .findFirst();
    }

    @Test
    @DisplayName("an issued punishment shows who, where, how long, why, by whom and from what")
    void issued() {
        EmbedData embed = DiscordPunishmentLog.issuedEmbed(ban("10.0.0.1", NOW.plus(Duration.ofDays(7))), false);

        assertEquals("Ban (with address) #7", embed.title().toOptional().orElseThrow());
        assertEquals("Not\\_ch (" + PLAYER + ") and hidden address", field(embed, "Player").orElseThrow());
        assertEquals("survival", field(embed, "Where").orElseThrow());
        assertEquals("1w, ends <t:" + NOW.plus(Duration.ofDays(7)).getEpochSecond() + ":R>",
                field(embed, "Length").orElseThrow());
        // Shown as written: embeds never ping, and the message allows no mentions.
        assertEquals("x-ray @everyone", field(embed, "Reason").orElseThrow());
        assertEquals("Mod (discord)", field(embed, "Issued by").orElseThrow());
        assertEquals("#hacking", field(embed, "Template").orElseThrow());
        assertEquals("#3", field(embed, "Because of").orElseThrow());
    }

    @Test
    @DisplayName("addresses are shown only when configured; a permanent ban says so")
    void addresses() {
        EmbedData embed = DiscordPunishmentLog.issuedEmbed(ban("10.0.0.1", null), true);
        assertTrue(field(embed, "Player").orElseThrow().endsWith("and 10.0.0.1"));
        assertEquals("Permanent", field(embed, "Length").orElseThrow());
    }

    @Test
    @DisplayName("a lifted punishment says who lifted it and why")
    void revoked() {
        Punishment lifted = ban(null, null).withRevocation(
                new Punishment.Revocation(NOW.plusSeconds(60), IssuerRef.console(), "appeal accepted"));

        EmbedData embed = DiscordPunishmentLog.revokedEmbed(lifted, false);

        assertEquals("Ban #7 lifted", embed.title().toOptional().orElseThrow());
        assertEquals("Console (console)", field(embed, "Lifted by").orElseThrow());
        assertEquals("appeal accepted", field(embed, "Why lifted").orElseThrow());
        assertEquals("Not\\_ch (" + PLAYER + ")", field(embed, "Player").orElseThrow());
    }
}
