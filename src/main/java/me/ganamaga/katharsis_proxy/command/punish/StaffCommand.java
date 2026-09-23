package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.PunishPermissions;
import me.ganamaga.katharsis_proxy.punish.ScopeResolver;
import me.ganamaga.katharsis_proxy.punish.Target;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Base of the punishment commands.
 *
 * <p>Unlike the plugin's player commands, these are closed by default: only
 * those granted {@code katharsis.punish.<command>} may run them, or see them
 * in tab completion.</p>
 */
abstract class StaffCommand implements SimpleCommand {

    /** Durations offered by tab completion. */
    private static final List<String> DURATIONS = List.of("30m", "1h", "1d", "7d", "30d", "perm");

    protected final PunishContext context;
    private final String permission;
    private final String usage;

    /**
     * @param node  the last part of the permission node, e.g. {@code ban}
     * @param usage the syntax, shown when the command is used wrongly
     */
    StaffCommand(PunishContext context, String node, String usage) {
        this.context = context;
        this.permission = PunishPermissions.command(node);
        this.usage = usage;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(permission);
    }

    protected void sendUsage(CommandSource source) {
        send(source, MessageKey.PUNISH_USAGE, Placeholder.unparsed("usage", usage));
    }

    protected void send(CommandSource source, MessageKey key,
                        TagResolver... placeholders) {
        context.messages().send(source, key, placeholders);
    }

    protected String language(CommandSource source) {
        return context.messages().languageOf(source);
    }

    /**
     * Looks up whom {@code args} names, telling {@code source} if nobody by
     * that name is known. Blocks on the database.
     */
    protected Optional<Target> resolve(CommandSource source, PunishArgs args) {
        try {
            Optional<Target> target = context.targets().resolve(args.target(), args.force());
            if (target.isEmpty()) {
                send(source, MessageKey.PUNISH_UNKNOWN_PLAYER, Placeholder.unparsed("player", args.target()));
            }
            return target;
        } catch (SQLException e) {
            context.logger().error("Could not look up {}.", args.target(), e);
            send(source, MessageKey.PUNISH_UNAVAILABLE);
            return Optional.empty();
        }
    }

    /**
     * Where a new punishment from {@code source} applies: as the flags say,
     * else as {@code fallback} (a template's scope) says, else by the
     * configured default. Tells {@code source} and returns empty if the scope
     * named is unknown or they may not punish network-wide.
     *
     * @param fallback a scope as typed after {@code -s}, or {@code null}
     */
    protected Optional<PunishmentScope> issueScope(CommandSource source, PunishArgs args, String fallback) {
        Optional<PunishmentScope> scope = explicitScope(source, args);
        if (scope == null && fallback != null) {
            scope = context.scopes().parse(fallback, context.serverNames());
            if (scope.isEmpty()) {
                send(source, MessageKey.PUNISH_UNKNOWN_SCOPE, Placeholder.unparsed("scope", fallback));
            }
        }
        if (scope == null) {
            scope = Optional.of(defaultScope(source));
        }
        if (scope.isPresent() && scope.get().isGlobal() && source instanceof Player player
                && !player.hasPermission(PunishPermissions.GLOBAL)) {
            send(source, MessageKey.PUNISH_NO_GLOBAL_PERMISSION);
            return Optional.empty();
        }
        return scope;
    }

    /**
     * The scope the flags name, empty (after telling {@code source}) if it is
     * unknown, or {@code null} if the flags name none.
     */
    protected Optional<PunishmentScope> explicitScope(CommandSource source, PunishArgs args) {
        if (args.global()) {
            return Optional.of(PunishmentScope.global());
        }
        if (args.scope() == null) {
            return null;
        }
        Optional<PunishmentScope> scope = context.scopes().parse(args.scope(), context.serverNames());
        if (scope.isEmpty()) {
            send(source, MessageKey.PUNISH_UNKNOWN_SCOPE, Placeholder.unparsed("scope", args.scope()));
        }
        return scope;
    }

    private PunishmentScope defaultScope(CommandSource source) {
        PunishmentsConfig config = context.config().get();
        if (source instanceof Player player) {
            if (config.defaultScope() == PunishmentsConfig.DefaultScope.GLOBAL) {
                return PunishmentScope.global();
            }
            return player.getCurrentServer()
                    .map(server -> context.scopes().defaultFor(server.getServerInfo().getName(),
                            config.defaultScopeUsesGroup()))
                    .orElse(PunishmentScope.global());
        }
        return context.scopes().parse(config.consoleDefaultScope(), context.serverNames())
                .orElse(PunishmentScope.global());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasPermission(invocation)) {
            return List.of();
        }
        String[] arguments = invocation.arguments();
        String current = arguments.length == 0 ? "" : arguments[arguments.length - 1];
        String previous = arguments.length < 2 ? "" : arguments[arguments.length - 2];
        Stream<String> options;
        if (previous.equalsIgnoreCase("-s")) {
            List<String> scopes = new ArrayList<>(context.serverNames());
            scopes.addAll(context.scopes().groupNames());
            scopes.add(ScopeResolver.GLOBAL);
            options = scopes.stream();
        } else if (current.startsWith("-")) {
            options = PunishArgs.FLAGS.stream();
        } else if (current.startsWith("#") && takesTemplate()) {
            options = context.templates().names().stream().map(name -> "#" + name);
        } else if (arguments.length <= 1) {
            options = context.proxy().getAllPlayers().stream().map(Player::getUsername);
        } else if (arguments.length == 2 && takesDuration()) {
            options = DURATIONS.stream();
        } else {
            return List.of();
        }
        String prefix = current.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    /** Whether a {@code #template} may be given, for tab completion. */
    protected boolean takesTemplate() {
        return false;
    }

    /** Whether the word after the target is a duration, for tab completion. */
    protected boolean takesDuration() {
        return false;
    }
}
