package me.ganamaga.katharsis_proxy.discord;

import discord4j.common.util.Snowflake;
import discord4j.discordjson.json.AllowedMentionsData;
import discord4j.discordjson.json.EmbedData;
import discord4j.discordjson.json.EmbedFieldData;
import discord4j.discordjson.json.ImmutableEmbedData;
import discord4j.discordjson.json.MessageCreateRequest;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.punish.DurationParser;
import me.ganamaga.katharsis_proxy.punish.PunishmentLength;
import me.ganamaga.katharsis_proxy.punish.PunishmentObserver;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Posts every punishment issued and lifted to the staff log channel on
 * Discord, as an embed.
 *
 * <p>Names and reasons are shown escaped and notify nobody, as in
 * {@link DiscordModerationAlert}. Addresses are hidden unless
 * {@code punishments.discord.log-ips} is on.</p>
 */
public final class DiscordPunishmentLog implements PunishmentObserver {

    private static final AllowedMentionsData NO_MENTIONS = AllowedMentionsData.builder().parse(List.of()).build();

    private static final int RED = 0xE7_4C_3C;
    private static final int ORANGE = 0xE6_7E_22;
    private static final int YELLOW = 0xF1_C4_0F;
    private static final int BLUE = 0x34_98_DB;
    private static final int GREEN = 0x2E_CC_71;

    private final Logger logger;
    private final DiscordBot bot;
    private final Supplier<PunishmentsConfig> config;
    private final LongSupplier fallbackChannel;

    /**
     * @param fallbackChannel the moderation staff channel, used when no log channel is set
     */
    public DiscordPunishmentLog(Logger logger, DiscordBot bot, Supplier<PunishmentsConfig> config,
                                LongSupplier fallbackChannel) {
        this.logger = logger;
        this.bot = bot;
        this.config = config;
        this.fallbackChannel = fallbackChannel;
    }

    @Override
    public void issued(Punishment punishment) {
        post(issuedEmbed(punishment, config.get().discord().logIps()), punishment);
    }

    @Override
    public void revoked(Punishment punishment) {
        post(revokedEmbed(punishment, config.get().discord().logIps()), punishment);
    }

    /** The channel the log goes to, or empty when none is configured. */
    Optional<Snowflake> channel() {
        long channelId = config.get().discord().logChannelId();
        if (channelId <= 0L) {
            channelId = fallbackChannel.getAsLong();
        }
        return channelId <= 0L ? Optional.empty() : Optional.of(Snowflake.of(channelId));
    }

    private void post(EmbedData embed, Punishment punishment) {
        Optional<Snowflake> channel = channel();
        if (channel.isEmpty() || !bot.isAccepting()) {
            return;
        }
        MessageCreateRequest request = MessageCreateRequest.builder().embed(embed).allowedMentions(NO_MENTIONS).build();
        bot.submit(client -> client.getRestClient().getChannelById(channel.get()).createMessage(request))
                .exceptionally(e -> {
                    // A task that ran and failed is already logged by the bot.
                    if (e instanceof RejectedExecutionException) {
                        logger.debug("Did not log punishment #{} to Discord: {}", punishment.id(), e.getMessage());
                    }
                    return null;
                });
    }

    static EmbedData issuedEmbed(Punishment punishment, boolean showIps) {
        String type = typeName(punishment);
        ImmutableEmbedData.Builder embed = EmbedData.builder()
                .title(capitalise(type) + " #" + punishment.id())
                .color(switch (punishment.type()) {
                    case BAN -> RED;
                    case MUTE -> ORANGE;
                    case WARN -> YELLOW;
                    case KICK -> BLUE;
                })
                .timestamp(DateTimeFormatter.ISO_INSTANT.format(punishment.createdAt()))
                .addField(field("Player", target(punishment, showIps), false))
                .addField(field("Where", punishment.scope().describe(), true));
        if (punishment.type().isLasting()) {
            embed.addField(field("Length", length(punishment), true));
        }
        embed.addField(field("Reason", punishment.reason(), false))
                .addField(field("Issued by", issuer(punishment.issuer()), true));
        if (punishment.template() != null) {
            embed.addField(field("Template", "#" + punishment.template(), true));
        }
        if (punishment.parentId() != null) {
            embed.addField(field("Because of", "#" + punishment.parentId(), true));
        }
        if (punishment.silent()) {
            embed.addField(field("Quiet", "Staff in game were not told", true));
        }
        return embed.build();
    }

    static EmbedData revokedEmbed(Punishment punishment, boolean showIps) {
        Punishment.Revocation revocation = punishment.revocation();
        ImmutableEmbedData.Builder embed = EmbedData.builder()
                .title(capitalise(typeName(punishment)) + " #" + punishment.id() + " lifted")
                .color(GREEN)
                .timestamp(DateTimeFormatter.ISO_INSTANT.format(revocation.at()))
                .addField(field("Player", target(punishment, showIps), false))
                .addField(field("Where", punishment.scope().describe(), true))
                .addField(field("Lifted by", issuer(revocation.by()), true))
                .addField(field("Original reason", punishment.reason(), false));
        if (revocation.reason() != null && !revocation.reason().isBlank()) {
            embed.addField(field("Why lifted", revocation.reason(), false));
        }
        return embed.build();
    }

    private static String typeName(Punishment punishment) {
        String type = punishment.type().name().toLowerCase(Locale.ROOT);
        if (punishment.targetUuid() == null) {
            return "address " + type;
        }
        return punishment.targetIp() != null ? type + " (with address)" : type;
    }

    private static String target(Punishment punishment, boolean showIps) {
        String address = punishment.targetIp() == null ? null : showIps ? punishment.targetIp() : "hidden address";
        if (punishment.targetUuid() == null) {
            return address;
        }
        String account = punishment.targetName() + " (" + punishment.targetUuid() + ")";
        return address == null ? account : account + " and " + address;
    }

    private static String length(Punishment punishment) {
        if (punishment.isPermanent()) {
            return "Permanent";
        }
        String total = DurationParser.format(PunishmentLength.of(
                Duration.between(punishment.createdAt(), punishment.expiresAt())));
        // Discord shows this in every reader's own time zone, and counts down.
        return total + ", ends <t:" + punishment.expiresAt().getEpochSecond() + ":R>";
    }

    private static String issuer(IssuerRef issuer) {
        String source = issuer.source().name().toLowerCase(Locale.ROOT);
        return issuer.name() + " (" + source + ")";
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Values are escaped; the length's timestamp tag must survive, so it is not. */
    private static EmbedFieldData field(String name, String value, boolean inline) {
        String shown = name.equals("Length") ? value : DiscordChatRelay.escape(value);
        return EmbedFieldData.builder()
                .name(name)
                .value(DiscordModerationAlert.cut(shown))
                .inline(inline)
                .build();
    }
}
