package me.ganamaga.katharsis_proxy.discord;

import discord4j.common.util.Snowflake;
import discord4j.discordjson.json.AllowedMentionsData;
import discord4j.discordjson.json.EmbedData;
import discord4j.discordjson.json.EmbedFieldData;
import discord4j.discordjson.json.MessageCreateRequest;
import me.ganamaga.katharsis_proxy.config.ModerationConfig;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.moderation.ModerationAlerts;
import me.ganamaga.katharsis_proxy.moderation.ModerationSource;
import me.ganamaga.katharsis_proxy.moderation.ModerationVerdict;
import org.slf4j.Logger;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Reports held-back chat to the staff channel, as an embed carrying the player,
 * the server, what stopped it and the message itself.
 *
 * <p>The text is shown escaped and notifies nobody, exactly as in
 * {@link DiscordChatRelay}: a message being reported to moderators is no reason
 * for it to be able to ping them.</p>
 *
 * <p>Messages held back only because nothing could judge them, which is
 * {@link ModerationSource#UNAVAILABLE}, are not reported here. They say nothing
 * about the player, and an OpenAI outage would post one for every message
 * anyone writes.</p>
 */
public final class DiscordModerationAlert implements ModerationAlerts {

    /** Discord's limit on an embed field, with room left for the escaping. */
    static final int FIELD_MAX_LENGTH = 1_000;

    /** Red, so a flagged message stands out in the channel. */
    private static final int COLOR = 0xE7_4C_3C;

    private static final AllowedMentionsData NO_MENTIONS = AllowedMentionsData.builder().parse(List.of()).build();

    private final Logger logger;
    private final DiscordBot bot;
    private final Supplier<ModerationConfig> config;

    /**
     * @param config read on every alert, so a reloaded channel id applies at once
     */
    public DiscordModerationAlert(Logger logger, DiscordBot bot, Supplier<ModerationConfig> config) {
        this.logger = logger;
        this.bot = bot;
        this.config = config;
    }

    @Override
    public void flagged(ChatMessage message, ModerationVerdict verdict) {
        if (verdict.source() == null || !verdict.source().isViolation()) {
            return;
        }
        Optional<Snowflake> channel = channel();
        if (channel.isEmpty() || !bot.isAccepting()) {
            return;
        }

        MessageCreateRequest request = MessageCreateRequest.builder()
                .embed(embed(message, verdict))
                .allowedMentions(NO_MENTIONS)
                .build();
        bot.submit(client -> client.getRestClient()
                        .getChannelById(channel.get())
                        .createMessage(request))
                .exceptionally(e -> {
                    // A task that ran and failed is already logged by the bot.
                    if (e instanceof RejectedExecutionException) {
                        logger.debug("Did not report a flagged message from {} to Discord: {}",
                                message.playerName(), e.getMessage());
                    }
                    return null;
                });
    }

    /** The channel alerts go to, or empty when none is configured. */
    Optional<Snowflake> channel() {
        long channelId = config.get().staffChannelId();
        return channelId <= 0L ? Optional.empty() : Optional.of(Snowflake.of(channelId));
    }

    static EmbedData embed(ChatMessage message, ModerationVerdict verdict) {
        return EmbedData.builder()
                .title("Chat message held back")
                .color(COLOR)
                .timestamp(DateTimeFormatter.ISO_INSTANT.format(message.sentAt()))
                .addField(field("Player", message.playerName() + " (" + message.playerUuid() + ")", false))
                .addField(field("Server", message.server(), true))
                .addField(field("Caught by", describe(verdict), true))
                .addField(field("Message", message.content(), false))
                .build();
    }

    private static EmbedFieldData field(String name, String value, boolean inline) {
        return EmbedFieldData.builder()
                .name(name)
                .value(cut(DiscordChatRelay.escape(value)))
                .inline(inline)
                .build();
    }

    /** What stopped it, and what it objected to when it said. */
    private static String describe(ModerationVerdict verdict) {
        String source = verdict.source().id();
        return verdict.reason().isBlank() ? source : source + " — " + verdict.reason();
    }

    /**
     * Keeps a long message inside Discord's limit. Escaping can push the text
     * over it, so the cut happens after escaping: never between the halves of a
     * surrogate pair, and never leaving a backslash that would escape the
     * ellipsis instead of the character it was added for.
     */
    static String cut(String text) {
        if (text.isEmpty()) {
            // Discord rejects an empty field value outright.
            return "—";
        }
        if (text.length() <= FIELD_MAX_LENGTH) {
            return text;
        }
        int end = FIELD_MAX_LENGTH - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        while (end > 0 && text.charAt(end - 1) == '\\') {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
