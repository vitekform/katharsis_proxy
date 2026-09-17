package me.ganamaga.katharsis_proxy.discord;

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.entity.User;
import discord4j.discordjson.json.ApplicationCommandRequest;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.link.AccountLinkService;
import me.ganamaga.katharsis_proxy.link.IssueResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The Discord half of account linking: the {@code /link} slash command, which
 * privately hands the user a one-time code, and granting the linked role.
 */
public final class DiscordAccountLinking {

    static final String COMMAND_NAME = "link";

    private final Logger logger;
    private final DiscordBot bot;
    private final AccountLinkService links;
    private final Messages messages;
    private final Supplier<DiscordConfig> config;

    public DiscordAccountLinking(Logger logger, DiscordBot bot, AccountLinkService links, Messages messages,
                                 Supplier<DiscordConfig> config) {
        this.logger = logger;
        this.bot = bot;
        this.links = links;
        this.messages = messages;
        this.config = config;
    }

    /**
     * Registers {@code /link} in the configured Discord server and starts
     * answering it, once the bot has logged in. Does nothing while the bot is
     * off or no server is configured.
     */
    public void register() {
        long guildId = config.get().guildId();
        if (!bot.isAccepting() || guildId <= 0L) {
            return;
        }

        ApplicationCommandRequest command = ApplicationCommandRequest.builder()
                .name(COMMAND_NAME)
                .description(plain(messages.registry().defaultLanguage(), MessageKey.DISCORD_LINK_DESCRIPTION))
                .build();
        bot.submit(client -> {
            // on(type, handler) keeps listening after a handler fails.
            client.on(ChatInputInteractionEvent.class, this::handle).subscribe();
            // Creating a command under an existing name updates it in place, so
            // this is safe on every start.
            return client.getRestClient().getApplicationId()
                    .flatMap(applicationId -> client.getRestClient().getApplicationService()
                            .createGuildApplicationCommand(applicationId, guildId, command));
        }).thenRun(() -> logger.info("Registered /{} on the Discord server {}.", COMMAND_NAME, guildId));
    }

    /**
     * Gives the linked role to the Discord side of {@code link}, if one is
     * configured. Queued on the bot; failures, such as a missing permission or
     * a member who left the server, are logged there.
     */
    public void grantLinkedRole(AccountLink link) {
        DiscordConfig current = config.get();
        long guildId = current.guildId();
        long roleId = current.linkedRoleId();
        if (guildId <= 0L || roleId <= 0L || !bot.isAccepting()) {
            return;
        }
        // Adding a role the member already has is a no-op on Discord's side.
        bot.submit(client -> client.getRestClient().getGuildService().addGuildMemberRole(
                        guildId, link.discordUserId(), roleId, "Linked to Minecraft account " + link.playerName()))
                .thenRun(() -> logger.info("Granted the linked role to Discord user {} ({}).",
                        link.discordUsername(), link.discordUserId()));
    }

    private Mono<Void> handle(ChatInputInteractionEvent event) {
        if (!COMMAND_NAME.equals(event.getCommandName())) {
            return Mono.empty();
        }
        User user = event.getInteraction().getUser();
        String language = languageFor(event.getInteraction().getUserLocale(), messages.registry());

        // Discord wants an answer within three seconds, so acknowledge first,
        // then do the database work where blocking is allowed.
        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> reply(user, language)).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(event::editReply)
                .then();
    }

    private String reply(User user, String language) {
        if (!links.isAvailable()) {
            return plain(language, MessageKey.DISCORD_LINK_UNAVAILABLE);
        }
        try {
            return switch (links.issueCode(user.getId().asLong(), user.getUsername())) {
                case IssueResult.Issued issued -> {
                    logger.info("Issued a link code to Discord user {} ({}).",
                            user.getUsername(), user.getId().asString());
                    yield plain(language, MessageKey.DISCORD_LINK_CODE,
                            Placeholder.unparsed("code", issued.code().code()),
                            Placeholder.unparsed("hours", Long.toString(AccountLinkService.CODE_VALIDITY.toHours())));
                }
                case IssueResult.AlreadyLinked already -> {
                    // Heals a role that was removed, or never granted because
                    // the bot lacked permission at the time.
                    grantLinkedRole(already.link());
                    yield plain(language, MessageKey.DISCORD_LINK_ALREADY_LINKED,
                            Placeholder.unparsed("player", DiscordChatRelay.escape(already.link().playerName())));
                }
            };
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Could not issue a link code to Discord user {} ({}).",
                    user.getUsername(), user.getId().asString(), e);
            return plain(language, MessageKey.DISCORD_LINK_UNAVAILABLE);
        }
    }

    /** Renders a message as plain text, keeping the Discord markdown the template contains. */
    private String plain(String language, MessageKey key, TagResolver... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(messages.render(language, key, placeholders));
    }

    /**
     * Matches a Discord locale such as {@code en-US} or {@code cs} to a loaded
     * language: exactly if possible, else by its language alone, else the
     * default.
     */
    static String languageFor(String discordLocale, LanguageRegistry registry) {
        if (discordLocale == null || discordLocale.isBlank()) {
            return registry.defaultLanguage();
        }
        String exact = LanguageRegistry.normalise(discordLocale);
        if (registry.isKnown(exact)) {
            return exact;
        }
        String prefix = discordLocale.split("[-_]", 2)[0].toLowerCase(Locale.ROOT) + "_";
        String defaultLanguage = registry.defaultLanguage();
        if (defaultLanguage.startsWith(prefix)) {
            return defaultLanguage;
        }
        return registry.languages().stream()
                .filter(language -> language.startsWith(prefix))
                .findFirst()
                .orElse(defaultLanguage);
    }
}
