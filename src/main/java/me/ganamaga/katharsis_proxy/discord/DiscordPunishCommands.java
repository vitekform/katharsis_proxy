package me.ganamaga.katharsis_proxy.discord;

import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.link.AccountLinkService;
import me.ganamaga.katharsis_proxy.permission.PermissionLookup;
import me.ganamaga.katharsis_proxy.punish.DurationParser;
import me.ganamaga.katharsis_proxy.punish.IpMode;
import me.ganamaga.katharsis_proxy.punish.IssueResult;
import me.ganamaga.katharsis_proxy.punish.PunishPermissions;
import me.ganamaga.katharsis_proxy.punish.PunishmentLength;
import me.ganamaga.katharsis_proxy.punish.PunishmentRequest;
import me.ganamaga.katharsis_proxy.punish.PunishmentService;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.RevokeRequest;
import me.ganamaga.katharsis_proxy.punish.RevokeResult;
import me.ganamaga.katharsis_proxy.punish.ScopeResolver;
import me.ganamaga.katharsis_proxy.punish.Target;
import me.ganamaga.katharsis_proxy.punish.TargetResolver;
import me.ganamaga.katharsis_proxy.punish.TemplateRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code /punish} on Discord: {@code ban}, {@code mute}, {@code warn},
 * {@code kick}, {@code unban}, {@code unmute}, {@code unwarn}, {@code history}
 * and {@code check}, working as their in-game counterparts do.
 *
 * <p>Who may use them is decided by {@link DiscordPunishAuth}. Replies are
 * only shown to the member who asked; the punishment log and staff in game
 * hear of what was done as usual.</p>
 */
public final class DiscordPunishCommands {

    static final String COMMAND_NAME = "punish";

    /**
     * Moderate Members, as Discord writes permissions: members without it do
     * not see the command unless the server's settings say otherwise. Only a
     * convenience; every use is still checked.
     */
    static final String MODERATE_MEMBERS = "1099511627776";

    /** Below Discord's 2000 characters per message. */
    private static final int REPLY_MAX_LENGTH = 1_900;

    private static final int HISTORY_PAGE_SIZE = 10;

    /**
     * What the subcommands need from the punishment system.
     *
     * @param servers the {@code velocity.toml} server names
     * @param online  whether a player is online, for kicks
     */
    public record Punishments(PunishmentService service, TargetResolver targets, ScopeResolver scopes,
                              TemplateRegistry templates, PunishmentText text, Supplier<Collection<String>> servers,
                              Predicate<UUID> online) {
    }

    private final Logger logger;
    private final DiscordBot bot;
    private final Messages messages;
    private final Supplier<DiscordConfig> discord;
    private final Supplier<PunishmentsConfig> config;
    private final AccountLinkService links;
    private final PermissionLookup permissions;
    private final Punishments punishments;

    public DiscordPunishCommands(Logger logger, DiscordBot bot, Messages messages, Supplier<DiscordConfig> discord,
                                 Supplier<PunishmentsConfig> config, AccountLinkService links,
                                 PermissionLookup permissions, Punishments punishments) {
        this.logger = logger;
        this.bot = bot;
        this.messages = messages;
        this.discord = discord;
        this.config = config;
        this.links = links;
        this.permissions = permissions;
        this.punishments = punishments;
    }

    /**
     * Registers {@code /punish} in the configured Discord server and starts
     * answering it, once the bot has logged in.
     *
     * @return whether it is being registered; not while the bot is off, no
     *         server is configured, or the commands are turned off
     */
    public boolean register() {
        long guildId = discord.get().guildId();
        if (!bot.isAccepting() || guildId <= 0L || !config.get().discord().commandsEnabled()) {
            return false;
        }
        ApplicationCommandRequest command = ApplicationCommandRequest.builder()
                .name(COMMAND_NAME)
                // Shown as written, so not escaped.
                .description(PlainTextComponentSerializer.plainText().serialize(messages.render(
                        messages.registry().defaultLanguage(), MessageKey.DISCORD_PUNISH_DESCRIPTION)))
                .defaultMemberPermissions(MODERATE_MEMBERS)
                .dmPermission(false)
                .addOption(subcommand("ban", "Ban a player, or an address",
                        player("Player name or address"), reason(), duration(), template(), scope(), accountOnly()))
                .addOption(subcommand("mute", "Mute a player, or an address",
                        player("Player name or address"), reason(), duration(), template(), scope(), accountOnly()))
                .addOption(subcommand("warn", "Warn a player",
                        player("Player name"), reason(), template(), scope()))
                .addOption(subcommand("kick", "Kick a player who is online", player("Player name"), reason()))
                .addOption(subcommand("unban", "Lift bans",
                        player("Player name, address, or #id of one ban"), reason(), scope()))
                .addOption(subcommand("unmute", "Lift mutes",
                        player("Player name, address, or #id of one mute"), reason(), scope()))
                .addOption(subcommand("unwarn", "Lift a player's newest warning",
                        player("Player name, or #id of one warning"), reason()))
                .addOption(subcommand("history", "Everything a player was ever given",
                        player("Player name or address"),
                        option("page", "Page, 1 for the newest", ApplicationCommandOption.Type.INTEGER, false)))
                .addOption(subcommand("check", "What is in force against a player now",
                        player("Player name or address")))
                .build();
        bot.submit(client -> {
            // on(type, handler) keeps listening after a handler fails.
            client.on(ChatInputInteractionEvent.class, this::handle).subscribe();
            // Creating a command under an existing name updates it in place,
            // and leaves the plugin's other commands alone.
            return client.getRestClient().getApplicationId()
                    .flatMap(applicationId -> client.getRestClient().getApplicationService()
                            .createGuildApplicationCommand(applicationId, guildId, command));
        }).thenRun(() -> logger.info("Registered /{} on the Discord server {}.", COMMAND_NAME, guildId));
        return true;
    }

    private static ApplicationCommandOptionData subcommand(String name, String description,
                                                           ApplicationCommandOptionData... options) {
        return ApplicationCommandOptionData.builder()
                .type(ApplicationCommandOption.Type.SUB_COMMAND.getValue())
                .name(name)
                .description(description)
                .options(List.of(options))
                .build();
    }

    private static ApplicationCommandOptionData option(String name, String description,
                                                       ApplicationCommandOption.Type type, boolean required) {
        return ApplicationCommandOptionData.builder()
                .type(type.getValue())
                .name(name)
                .description(description)
                .required(required)
                .build();
    }

    private static ApplicationCommandOptionData player(String description) {
        return option("player", description, ApplicationCommandOption.Type.STRING, true);
    }

    private static ApplicationCommandOptionData reason() {
        return option("reason", "Why; with a template, added to its reason", ApplicationCommandOption.Type.STRING,
                false);
    }

    private static ApplicationCommandOptionData duration() {
        return option("duration", "How long, e.g. 30m, 7d or 1mo2w; permanent if left out",
                ApplicationCommandOption.Type.STRING, false);
    }

    private static ApplicationCommandOptionData template() {
        return option("template", "A punishment template, e.g. hacking", ApplicationCommandOption.Type.STRING, false);
    }

    private static ApplicationCommandOptionData scope() {
        return option("scope", "global, a server or a server group", ApplicationCommandOption.Type.STRING, false);
    }

    private static ApplicationCommandOptionData accountOnly() {
        return option("account_only", "Leave the player's address alone", ApplicationCommandOption.Type.BOOLEAN,
                false);
    }

    private Mono<Void> handle(ChatInputInteractionEvent event) {
        if (!COMMAND_NAME.equals(event.getCommandName()) || event.getOptions().isEmpty()) {
            return Mono.empty();
        }
        String language = DiscordAccountLinking.languageFor(event.getInteraction().getUserLocale(),
                messages.registry());
        Set<Long> roles = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream().map(Snowflake::asLong).collect(Collectors.toSet()))
                .orElse(Set.of());
        long userId = event.getInteraction().getUser().getId().asLong();
        ApplicationCommandInteractionOption subcommand = event.getOptions().get(0);

        // Discord wants an answer within three seconds, so acknowledge first,
        // then do the database work where blocking is allowed.
        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> reply(new Request(subcommand, language, roles, userId)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(event::editReply)
                .then();
    }

    /** One use of a subcommand. */
    private record Request(ApplicationCommandInteractionOption subcommand, String language, Set<Long> roles,
                           long discordUserId) {

        Optional<String> string(String name) {
            return subcommand.getOption(name)
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asString)
                    .map(String::strip)
                    .filter(value -> !value.isEmpty());
        }

        boolean flag(String name) {
            return subcommand.getOption(name)
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asBoolean)
                    .orElse(false);
        }

        String player() {
            return string("player").orElse("");
        }
    }

    String reply(Request request) {
        try {
            String text = switch (request.subcommand().getName()) {
                case "ban" -> issue(request, PunishmentType.BAN);
                case "mute" -> issue(request, PunishmentType.MUTE);
                case "warn" -> issue(request, PunishmentType.WARN);
                case "kick" -> kick(request);
                case "unban" -> revoke(request, PunishmentType.BAN);
                case "unmute" -> revoke(request, PunishmentType.MUTE);
                case "unwarn" -> revoke(request, PunishmentType.WARN);
                case "history" -> history(request);
                case "check" -> check(request);
                default -> "";
            };
            return cut(text);
        } catch (SQLException e) {
            logger.error("Could not answer /{} {} on Discord.", COMMAND_NAME, request.subcommand().getName(), e);
            return say(request, MessageKey.PUNISH_UNAVAILABLE);
        }
    }

    // --- subcommands ---------------------------------------------------------

    private String issue(Request request, PunishmentType type) throws SQLException {
        String language = request.language();
        TemplateRegistry.Template template = null;
        Optional<String> templateName = request.string("template");
        if (templateName.isPresent()) {
            Optional<TemplateRegistry.Template> found = punishments.templates().find(templateName.get());
            if (found.isEmpty()) {
                return say(request, MessageKey.PUNISH_UNKNOWN_TEMPLATE, Placeholder.unparsed("name",
                        templateName.get().replaceFirst("^#", "")));
            }
            template = found.get();
            if (template.type() != type) {
                return say(request, MessageKey.PUNISH_TEMPLATE_TYPE, Placeholder.unparsed("name", template.name()),
                        Placeholder.component("type", punishments.text().type(language, template.type())));
            }
        }

        PunishmentLength explicit = null;
        Optional<String> durationText = request.string("duration");
        if (durationText.isPresent() && type != PunishmentType.WARN) {
            Optional<PunishmentLength> parsed = DurationParser.parse(durationText.get());
            if (parsed.isEmpty()) {
                return say(request, MessageKey.PUNISH_INVALID_DURATION, Placeholder.unparsed("input",
                        durationText.get()));
            }
            explicit = parsed.get();
        }

        String scopeName = request.string("scope")
                .orElse(template != null && template.scope() != null ? template.scope()
                        : config.get().consoleDefaultScope());
        Optional<PunishmentScope> scope = punishments.scopes().parse(scopeName, punishments.servers().get());
        if (scope.isEmpty()) {
            return say(request, MessageKey.PUNISH_UNKNOWN_SCOPE, Placeholder.unparsed("scope", scopeName));
        }

        String node = type.name().toLowerCase(Locale.ROOT);
        if (explicit != null && !explicit.isPermanent()) {
            node = "temp" + node;
        }
        List<String> nodes = new ArrayList<>(List.of(PunishPermissions.command(node)));
        if (scope.get().isGlobal()) {
            nodes.add(PunishPermissions.GLOBAL);
        }
        Auth allowed = authorize(request, nodes);
        if (!allowed.allowed()) {
            return allowed.denial();
        }

        Optional<Target> resolved = punishments.targets().resolve(request.player(), false);
        if (resolved.isEmpty() || (type == PunishmentType.WARN && resolved.get().isAddress())) {
            return say(request, MessageKey.PUNISH_UNKNOWN_PLAYER, Placeholder.unparsed("player", request.player()));
        }
        Target target = resolved.get();

        PunishmentLength length = explicit;
        if (length == null && template != null && (type != PunishmentType.WARN || !template.ladder().isEmpty())) {
            length = template.lengthFor(punishments.service().templateCount(target.uuid(), template.name()));
        }
        if (length == null) {
            length = type == PunishmentType.WARN
                    ? DurationParser.parse(config.get().warns().expireAfter()).orElse(PunishmentLength.PERMANENT)
                    : PunishmentLength.PERMANENT;
        }

        Optional<String> extra = request.string("reason");
        TemplateRegistry.Template chosen = template;
        String reason = chosen == null
                ? extra.orElseGet(() -> config.get().defaultReason())
                : extra.map(text -> chosen.reason() + " - " + text).orElse(chosen.reason());
        IssueResult result = punishments.service().issue(new PunishmentRequest(type, target,
                ipMode(type, target, request.flag("account_only")), scope.get(), length, reason,
                template == null ? null : template.name(), allowed.issuer(), false, false, null));

        String reply = plain(punishments.text().issueResult(language, result, target.name(), type));
        if (result instanceof IssueResult.Issued issued && issued.addressSkipped()) {
            reply += "\n" + say(request, MessageKey.PUNISH_ADDRESS_SKIPPED,
                    Placeholder.unparsed("player", target.name()));
        }
        return reply;
    }

    private IpMode ipMode(PunishmentType type, Target target, boolean accountOnly) {
        if (target.isAddress()) {
            return IpMode.IP_ONLY;
        }
        boolean withAddress = !accountOnly && switch (type) {
            case BAN -> config.get().banIpByDefault();
            case MUTE -> config.get().muteIpByDefault();
            default -> false;
        };
        return withAddress ? IpMode.ACCOUNT_AND_IP : IpMode.ACCOUNT;
    }

    private String kick(Request request) throws SQLException {
        Auth allowed = authorize(request, List.of(PunishPermissions.command("kick")));
        if (!allowed.allowed()) {
            return allowed.denial();
        }
        Optional<Target> target = punishments.targets().resolve(request.player(), false)
                .filter(found -> !found.isAddress() && punishments.online().test(found.uuid()));
        if (target.isEmpty()) {
            return say(request, MessageKey.PUNISH_NOT_ONLINE, Placeholder.unparsed("player", request.player()));
        }
        String reason = request.string("reason").orElseGet(() -> config.get().defaultReason());
        IssueResult result = punishments.service().issue(new PunishmentRequest(PunishmentType.KICK, target.get(),
                IpMode.ACCOUNT, PunishmentScope.global(), PunishmentLength.PERMANENT, reason, null,
                allowed.issuer(), false, false, null));
        return plain(punishments.text().issueResult(request.language(), result, target.get().name(),
                PunishmentType.KICK));
    }

    private String revoke(Request request, PunishmentType type) throws SQLException {
        String node = "un" + type.name().toLowerCase(Locale.ROOT);
        Auth allowed = authorize(request, List.of(PunishPermissions.command(node)));
        if (!allowed.allowed()) {
            return allowed.denial();
        }
        String language = request.language();
        String reason = request.string("reason").orElse(null);
        Optional<Long> id = parseId(request.player());
        RevokeResult result;
        String name = request.player();
        if (id.isPresent()) {
            result = punishments.service().revoke(RevokeRequest.byId(type, id.get(), allowed.issuer(), reason));
        } else {
            PunishmentScope only = null;
            Optional<String> scopeName = request.string("scope");
            if (scopeName.isPresent()) {
                Optional<PunishmentScope> scope = punishments.scopes().parse(scopeName.get(),
                        punishments.servers().get());
                if (scope.isEmpty()) {
                    return say(request, MessageKey.PUNISH_UNKNOWN_SCOPE, Placeholder.unparsed("scope",
                            scopeName.get()));
                }
                only = scope.get();
            }
            Optional<Target> target = punishments.targets().resolve(request.player(), false);
            if (target.isEmpty()) {
                return say(request, MessageKey.PUNISH_UNKNOWN_PLAYER, Placeholder.unparsed("player", name));
            }
            name = target.get().name();
            result = punishments.service().revoke(type == PunishmentType.WARN && only == null
                    ? RevokeRequest.newest(type, target.get(), allowed.issuer(), reason)
                    : RevokeRequest.byTarget(type, target.get(), only, allowed.issuer(), reason));
        }
        Component typeName = punishments.text().type(language, type);
        return switch (result) {
            case RevokeResult.Revoked revoked -> say(request, MessageKey.PUNISH_REVOKED,
                    Placeholder.unparsed("player", revoked.punishments().get(0).targetName()),
                    Placeholder.component("type", typeName),
                    Placeholder.unparsed("count", Integer.toString(revoked.punishments().size())));
            case RevokeResult.NothingActive ignored -> id.isPresent()
                    ? say(request, MessageKey.PUNISH_UNKNOWN_ID, Placeholder.unparsed("id", Long.toString(id.get())),
                    Placeholder.component("type", typeName))
                    : say(request, MessageKey.PUNISH_NOTHING_ACTIVE, Placeholder.unparsed("player", name),
                    Placeholder.component("type", typeName));
            case RevokeResult.Unavailable ignored -> say(request, MessageKey.PUNISH_UNAVAILABLE);
        };
    }

    private String history(Request request) throws SQLException {
        Auth allowed = authorize(request, List.of(PunishPermissions.command("history")));
        if (!allowed.allowed()) {
            return allowed.denial();
        }
        Optional<Target> target = punishments.targets().resolve(request.player(), false);
        if (target.isEmpty()) {
            return say(request, MessageKey.PUNISH_UNKNOWN_PLAYER, Placeholder.unparsed("player", request.player()));
        }
        int total = punishments.service().countHistory(target.get());
        if (total == 0) {
            return say(request, MessageKey.PUNISH_HISTORY_EMPTY, Placeholder.unparsed("player", target.get().name()));
        }
        int pages = (total + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE;
        long requested = request.subcommand().getOption("page")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong)
                .orElse(1L);
        int page = (int) Math.max(1, Math.min(requested, pages));
        List<String> lines = new ArrayList<>();
        lines.add(say(request, MessageKey.PUNISH_HISTORY_HEADER, Placeholder.unparsed("player", target.get().name()),
                Placeholder.unparsed("page", Integer.toString(page)),
                Placeholder.unparsed("pages", Integer.toString(pages))));
        Instant now = punishments.service().clock().instant();
        for (Punishment entry : punishments.service().history(target.get(), HISTORY_PAGE_SIZE,
                (page - 1) * HISTORY_PAGE_SIZE)) {
            lines.add(plain(punishments.text().historyLine(request.language(), entry, now)));
        }
        return String.join("\n", lines);
    }

    private String check(Request request) throws SQLException {
        Auth allowed = authorize(request, List.of(PunishPermissions.command("check")));
        if (!allowed.allowed()) {
            return allowed.denial();
        }
        Optional<Target> target = punishments.targets().resolve(request.player(), false);
        if (target.isEmpty()) {
            return say(request, MessageKey.PUNISH_UNKNOWN_PLAYER, Placeholder.unparsed("player", request.player()));
        }
        List<String> lines = new ArrayList<>();
        lines.add(say(request, MessageKey.PUNISH_CHECK_HEADER, Placeholder.unparsed("player", target.get().name()),
                Placeholder.unparsed("uuid", target.get().isAddress() ? "" : target.get().uuid().toString())));
        List<Punishment> active = punishments.service().activeAgainst(target.get().uuid(), target.get().ip());
        if (active.isEmpty()) {
            lines.add(say(request, MessageKey.PUNISH_CHECK_NONE));
        }
        Instant now = punishments.service().clock().instant();
        for (Punishment punishment : active) {
            lines.add(plain(punishments.text().activeLine(request.language(), punishment, now)));
        }
        return String.join("\n", lines);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Whether a member may go ahead.
     *
     * @param issuer who they act as, or {@code null} if they may not
     * @param denial what to tell them if they may not
     */
    private record Auth(IssuerRef issuer, String denial) {

        boolean allowed() {
            return issuer != null;
        }
    }

    /** Checks the member may use a subcommand needing {@code nodes}. */
    private Auth authorize(Request request, List<String> nodes) throws SQLException {
        DiscordPunishAuth.Decision decision = DiscordPunishAuth.decide(request.roles(),
                config.get().discord().staffRoleIds(), links.linkOfDiscordUser(request.discordUserId()), permissions,
                nodes);
        return switch (decision) {
            case DiscordPunishAuth.Decision.Allowed ok -> new Auth(ok.issuer(), null);
            case DiscordPunishAuth.Decision.NoRole ignored ->
                    new Auth(null, say(request, MessageKey.DISCORD_PUNISH_NO_ROLE));
            case DiscordPunishAuth.Decision.NotLinked ignored ->
                    new Auth(null, say(request, MessageKey.DISCORD_PUNISH_NOT_LINKED));
            case DiscordPunishAuth.Decision.NoPermission missing -> new Auth(null, say(request,
                    MessageKey.DISCORD_PUNISH_NO_PERMISSION, Placeholder.unparsed("node", missing.node())));
        };
    }

    private String say(Request request, MessageKey key, TagResolver... placeholders) {
        return plain(messages.render(request.language(), key, placeholders));
    }

    /** Plain text, escaped, so names and reasons cannot turn into Discord formatting or pings. */
    private static String plain(Component component) {
        return DiscordChatRelay.escape(PlainTextComponentSerializer.plainText().serialize(component));
    }

    private static String cut(String text) {
        if (text.length() <= REPLY_MAX_LENGTH) {
            return text;
        }
        int end = text.lastIndexOf('\n', REPLY_MAX_LENGTH);
        return text.substring(0, end > 0 ? end : REPLY_MAX_LENGTH) + "\n…";
    }

    /** {@code #123} as 123. */
    static Optional<Long> parseId(String input) {
        if (input.length() < 2 || input.charAt(0) != '#') {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(input.substring(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
