package me.ganamaga.katharsis_proxy.discord;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Attachment;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.PartialSticker;
import discord4j.core.object.entity.User;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.link.AccountLinkService;
import me.ganamaga.katharsis_proxy.messaging.CompanionRegistry;
import me.ganamaga.katharsis_proxy.player.PlayerDiscordChatCache;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Delivers what members write in the Discord chat channels to the players on
 * the servers mapped to those channels, as the reverse of
 * {@link DiscordChatRelay}.
 *
 * <p>The proxy sends the message to each player itself, in their language, so
 * it works on backends without the companion and with nobody to carry a plugin
 * message. Messages from bots, this one included, and webhooks are ignored, so
 * chat posted from the game never echoes back into it.</p>
 *
 * <p>Only linked members can see the chat channels, so the author is shown by
 * their linked Minecraft name. Should someone unlinked get in anyway, or the
 * database be down, their Discord display name is used instead.</p>
 *
 * <p>Players only see these messages once they turn Discord chat on, which
 * they are asked about on their first join and can change in
 * {@code /settings}.</p>
 */
public final class DiscordInboundChat {

    /** Longest text shown in game, so one Discord message cannot flood the chat. */
    static final int CONTENT_MAX_LENGTH = 256;

    /** {@code <@id>} and the legacy nickname form {@code <@!id>}. */
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");

    private static final Pattern ROLE_MENTION = Pattern.compile("<@&\\d+>");

    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#\\d+>");

    /** Custom and animated custom emoji, {@code <:name:id>} and {@code <a:name:id>}. */
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?(:\\w+:)\\d+>");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** Ordinary messages and replies; not joins, pins, boosts and the like. */
    private static final Set<Message.Type> CHAT_TYPES = Set.of(Message.Type.DEFAULT, Message.Type.REPLY);

    private final Logger logger;
    private final ProxyServer proxy;
    private final DiscordBot bot;
    private final AccountLinkService links;
    private final Messages messages;
    private final CompanionRegistry companions;
    private final PlayerDiscordChatCache discordChat;
    private final Supplier<DiscordConfig> config;

    /**
     * @param discordChat which players have Discord chat shown
     * @param config      read on every message, so a reloaded channel map applies at once
     */
    public DiscordInboundChat(Logger logger, ProxyServer proxy, DiscordBot bot, AccountLinkService links,
                              Messages messages, CompanionRegistry companions, PlayerDiscordChatCache discordChat,
                              Supplier<DiscordConfig> config) {
        this.logger = logger;
        this.proxy = proxy;
        this.bot = bot;
        this.links = links;
        this.messages = messages;
        this.companions = companions;
        this.discordChat = discordChat;
        this.config = config;
    }

    /**
     * Starts listening for messages once the bot has logged in. Does nothing
     * while the bot is off or {@code discord.relay-to-minecraft} is.
     *
     * @return whether it is listening
     */
    public boolean register() {
        if (!config.get().relayToMinecraft() || !bot.isAccepting()) {
            return false;
        }
        bot.submit(client -> {
            // on(type, handler) keeps listening after a handler fails.
            client.on(MessageCreateEvent.class, this::handle).subscribe();
            return Mono.empty();
        });
        return true;
    }

    private Mono<Void> handle(MessageCreateEvent event) {
        Message message = event.getMessage();
        Set<String> serverIds = serverIdsFor(message.getChannelId().asLong());
        if (serverIds.isEmpty() || !CHAT_TYPES.contains(message.getType())
                || message.getWebhookId().isPresent()) {
            return Mono.empty();
        }
        Optional<User> author = message.getAuthor();
        if (author.isEmpty() || author.get().isBot()) {
            return Mono.empty();
        }

        String content = plainContent(message);
        if (content.isEmpty()) {
            return Mono.empty();
        }
        User user = author.get();
        String displayName = event.getMember().map(Member::getDisplayName).orElseGet(() -> displayName(user));

        // The linked name comes from the database, which must not block the
        // gateway's threads.
        return Mono.fromCallable(() -> senderName(user.getId().asLong(), displayName))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(sender -> deliver(serverIds, sender, content))
                .then();
    }

    /** The author's linked Minecraft name, or {@code fallback} if there is none to be had. */
    private String senderName(long discordUserId, String fallback) {
        if (!links.isAvailable()) {
            return fallback;
        }
        try {
            return links.linkOfDiscordUser(discordUserId).map(AccountLink::playerName).orElse(fallback);
        } catch (SQLException e) {
            logger.warn("Could not look up the Minecraft account linked to Discord user {}; showing {} instead.",
                    discordUserId, fallback, e);
            return fallback;
        }
    }

    /**
     * Sends the message to every player on a backend reporting as one of
     * {@code serverIds} who has Discord chat shown, each in their own language.
     *
     * @return how many players it was sent to
     */
    int deliver(Set<String> serverIds, String sender, String content) {
        List<RegisteredServer> all = List.copyOf(proxy.getAllServers());
        Set<String> targets = companions.serversWithIds(
                all.stream().map(server -> server.getServerInfo().getName()).toList(), serverIds);

        int sent = 0;
        for (RegisteredServer server : all) {
            if (!targets.contains(server.getServerInfo().getName())) {
                continue;
            }
            for (Player player : server.getPlayersConnected()) {
                if (!discordChat.isShown(player.getUniqueId())) {
                    continue;
                }
                player.sendMessage(messages.render(messages.languageOf(player), MessageKey.DISCORD_CHAT_MESSAGE,
                        Placeholder.unparsed("player", sender),
                        Placeholder.unparsed("message", content)));
                sent++;
            }
        }
        logger.debug("Delivered a Discord message from {} to {} player(s) on {}.", sender, sent, targets);
        return sent;
    }

    /**
     * Every server id whose chat channel is {@code channelId}. Several servers
     * may share one channel, and all of them get its messages.
     */
    Set<String> serverIdsFor(long channelId) {
        return config.get().chatChannels().entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() == channelId)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String plainContent(Message message) {
        Map<Long, String> mentioned = message.getUserMentions().stream()
                .collect(Collectors.toMap(user -> user.getId().asLong(), DiscordInboundChat::displayName,
                        (first, second) -> first));
        List<String> extras = new ArrayList<>();
        message.getAttachments().stream().map(Attachment::getFilename).forEach(extras::add);
        message.getStickersItems().stream().map(PartialSticker::getName).forEach(extras::add);
        return plainContent(message.getContent(), mentioned, extras);
    }

    /**
     * Turns Discord's raw message text into what a player should read:
     * mentions and custom emoji by name, attachments and stickers as
     * {@code [name]}, on one line and at most {@value #CONTENT_MAX_LENGTH}
     * characters.
     *
     * @param mentioned display names of the users mentioned, by user id
     * @param extras    names of attachments and stickers, appended in brackets
     * @return the text, empty when there is nothing to show
     */
    static String plainContent(String content, Map<Long, String> mentioned, List<String> extras) {
        String text = USER_MENTION.matcher(content).replaceAll(match -> {
            String name = mentioned.get(Long.parseLong(match.group(1)));
            return Matcher.quoteReplacement("@" + (name == null ? "unknown" : name));
        });
        text = ROLE_MENTION.matcher(text).replaceAll("@role");
        text = CHANNEL_MENTION.matcher(text).replaceAll("#channel");
        text = CUSTOM_EMOJI.matcher(text).replaceAll("$1");

        StringBuilder all = new StringBuilder(text);
        for (String extra : extras) {
            all.append(" [").append(extra).append(']');
        }
        return truncate(WHITESPACE_RUN.matcher(all).replaceAll(" ").strip());
    }

    private static String truncate(String text) {
        if (text.length() <= CONTENT_MAX_LENGTH) {
            return text;
        }
        int end = CONTENT_MAX_LENGTH - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /** A user's name as Discord shows it outside any server. */
    private static String displayName(User user) {
        return user.getGlobalName().orElse(user.getUsername());
    }
}
