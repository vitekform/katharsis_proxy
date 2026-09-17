package me.ganamaga.katharsis_proxy.discord;

import discord4j.common.util.Snowflake;
import discord4j.discordjson.json.AllowedMentionsData;
import discord4j.discordjson.json.MessageCreateRequest;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.messaging.ChatRelay;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts reported chat to the Discord channel configured for its server, as
 * {@code **player:** message}.
 *
 * <p>The text is posted exactly as it was written in game: Discord formatting
 * is escaped, and mentions are shown but never notify anyone, so no player can
 * ping {@code @everyone} from chat.</p>
 */
public final class DiscordChatRelay implements ChatRelay {

    /** Discord's limit on a message's length. */
    static final int MESSAGE_MAX_LENGTH = 2_000;

    /**
     * Characters Discord reads as formatting mid-line: emphasis, code,
     * spoilers, masked links, and {@code <@id>}-style mentions and timestamps.
     * Line-start syntax such as headings cannot occur, because line breaks are
     * removed and every message starts with the player's name.
     */
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\*_~`|\\[\\]<]");

    private static final Pattern LINE_BREAKS = Pattern.compile("\\R");

    /** An empty parse list, so Discord notifies nobody. */
    private static final AllowedMentionsData NO_MENTIONS = AllowedMentionsData.builder().parse(List.of()).build();

    private final Logger logger;
    private final DiscordBot bot;
    private final Supplier<DiscordConfig> config;

    /**
     * @param config read on every message, so a reloaded channel map applies at once
     */
    public DiscordChatRelay(Logger logger, DiscordBot bot, Supplier<DiscordConfig> config) {
        this.logger = logger;
        this.bot = bot;
        this.config = config;
    }

    @Override
    public void relay(String serverId, ChatMessage message) {
        Optional<Snowflake> channel = channelFor(serverId);
        if (channel.isEmpty()) {
            // Only Discord is skipped; the listener still logs the message.
            logger.debug("Not posting chat from '{}' to Discord: no channel is configured for it.", serverId);
            return;
        }
        if (!bot.isAccepting()) {
            return;
        }

        MessageCreateRequest request = MessageCreateRequest.builder()
                .content(format(message.playerName(), message.content()))
                .allowedMentions(NO_MENTIONS)
                .build();
        bot.submit(client -> client.getRestClient()
                        .getChannelById(channel.get())
                        .createMessage(request))
                .exceptionally(e -> {
                    // A task that ran and failed is already logged by the bot.
                    if (e instanceof RejectedExecutionException) {
                        logger.debug("Did not post a chat message from {} to Discord: {}",
                                message.playerName(), e.getMessage());
                    }
                    return null;
                });
    }

    /**
     * The channel {@code serverId}'s chat goes to, or empty when the id is not
     * in {@code discord.chat-channels} or maps to something that is not a
     * channel id, in which case its chat is not posted.
     */
    Optional<Snowflake> channelFor(String serverId) {
        Long channelId = config.get().chatChannels().get(serverId);
        return channelId == null || channelId <= 0L ? Optional.empty() : Optional.of(Snowflake.of(channelId));
    }

    /** Builds {@code **player:** message}, escaped and cut to Discord's limit. */
    static String format(String playerName, String content) {
        String text = "**" + escape(playerName) + ":** " + escape(LINE_BREAKS.matcher(content).replaceAll(" "));
        if (text.length() <= MESSAGE_MAX_LENGTH) {
            return text;
        }
        int end = MESSAGE_MAX_LENGTH - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    static String escape(String text) {
        return MARKDOWN.matcher(text).replaceAll(match -> Matcher.quoteReplacement("\\" + match.group()));
    }
}
