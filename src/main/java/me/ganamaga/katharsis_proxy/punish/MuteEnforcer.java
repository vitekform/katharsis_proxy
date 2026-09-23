package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.messaging.PunishChannel;
import me.ganamaga.katharsis_proxy.messaging.PunishCodec;
import me.ganamaga.katharsis_proxy.util.CooldownManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enforces mutes.
 *
 * <p>The proxy cannot stop chat itself: cancelling a signed chat message on
 * its way through disconnects the client. So each player's mute is pushed to
 * the companion plugin on the server they are on, over
 * {@link PunishChannel#IDENTIFIER}, and the companion cancels their chat and
 * the configured commands there. It is pushed whenever a player reaches a
 * server, is muted or unmuted, or their mute runs out, and whenever the
 * companion asks, as it does when it starts. Where a mute applies is worked
 * out here, from the server the connection is to, which a backend cannot lie
 * about.</p>
 *
 * <p>Of the commands, only the proxy's own can be refused here, for the same
 * reason as chat; the companion refuses the backend's.</p>
 */
public final class MuteEnforcer implements PunishmentObserver {

    /** A muted player is reminded why at most this often. */
    static final Duration NOTICE_COOLDOWN = Duration.ofSeconds(3);

    private final ProxyServer proxy;
    private final Logger logger;
    private final PunishmentService service;
    private final ScopeResolver scopes;
    private final PunishmentText text;
    private final Supplier<PunishmentsConfig> config;
    private final CooldownManager notices = new CooldownManager(NOTICE_COOLDOWN);

    public MuteEnforcer(ProxyServer proxy, Logger logger, PunishmentService service, ScopeResolver scopes,
                        PunishmentText text, Supplier<PunishmentsConfig> config) {
        this.proxy = proxy;
        this.logger = logger;
        this.service = service;
        this.scopes = scopes;
        this.text = text;
        this.config = config;
    }

    /** The mute holding {@code player} on {@code server} longest, if any. */
    public Optional<Punishment> muteOn(Player player, String server) {
        return service.active().activeOn(player.getUniqueId(), PunishmentType.MUTE, server, scopes,
                service.clock().instant());
    }

    /** The mute holding {@code player} on the server they are on, if any. */
    public Optional<Punishment> muteHere(Player player) {
        return player.getCurrentServer().flatMap(server -> muteOn(player, server.getServerInfo().getName()));
    }

    /** Tells the server {@code player} is on whether they are muted there. */
    public void push(Player player) {
        player.getCurrentServer().ifPresent(server -> push(player, server));
    }

    private void push(Player player, ServerConnection server) {
        Punishment mute = muteOn(player, server.getServerInfo().getName()).orElse(null);
        server.sendPluginMessage(PunishChannel.IDENTIFIER,
                PunishCodec.encodeMuteState(player.getUniqueId(), mute, config.get().mutedCommands()));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        push(event.getPlayer());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!PunishChannel.IDENTIFIER.equals(event.getIdentifier())) {
            return;
        }
        // Proxy-internal: never relayed to the other side.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return; // a client speaking our channel
        }
        PunishCodec.Incoming message = PunishCodec.decode(event.getData());
        Player player = source.getPlayer();
        if (message == null || !message.uuid().equals(player.getUniqueId())) {
            // Only ever sent over the player's own connection.
            logger.debug("Ignored a malformed or misaddressed '{}' message from '{}'.",
                    PunishChannel.IDENTIFIER.getId(), source.getServerInfo().getName());
            return;
        }
        switch (message.operation()) {
            case PunishChannel.OP_MUTE_QUERY -> push(player, source);
            case PunishChannel.OP_MUTED_ATTEMPT -> muteOn(player, source.getServerInfo().getName())
                    .ifPresent(mute -> remind(player, mute));
            default -> logger.debug("Ignored unknown operation '{}' on '{}'.", message.operation(),
                    PunishChannel.IDENTIFIER.getId());
        }
    }

    /**
     * After the login checks, which refuse everything to players who have not
     * logged in. Only commands the proxy itself handles are refused; the rest
     * go on to the backend, whose companion refuses them.
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onCommand(CommandExecuteEvent event) {
        if (!event.getResult().isAllowed() || !(event.getCommandSource() instanceof Player player)) {
            return;
        }
        String alias = firstWord(event.getCommand());
        if (!isMutedCommand(alias, config.get().mutedCommands()) || !proxy.getCommandManager().hasCommand(alias)) {
            return;
        }
        muteHere(player).ifPresent(mute -> {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            remind(player, mute);
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        notices.clear(event.getPlayer().getUniqueId());
    }

    @Override
    public void issued(Punishment punishment) {
        if (punishment.type() != PunishmentType.MUTE) {
            return;
        }
        for (Player player : held(punishment)) {
            push(player);
            String language = text.messages().languageOf(player);
            player.sendMessage(text.messages().prefixed(language, MessageKey.PUNISH_YOU_WERE_MUTED,
                    Placeholder.component("scope", text.scope(language, punishment.scope())),
                    Placeholder.component("duration", text.length(language, punishment)),
                    Placeholder.unparsed("reason", punishment.reason())));
        }
    }

    @Override
    public void revoked(Punishment punishment) {
        if (punishment.type() == PunishmentType.MUTE) {
            held(punishment).forEach(this::lifted);
        }
    }

    /** Tells those whose mutes ran out, as {@link ActivePunishments#evictExpired} reported them. */
    public void expired(Map<UUID, List<Punishment>> expired) {
        expired.forEach((uuid, gone) -> {
            if (gone.stream().anyMatch(p -> p.type() == PunishmentType.MUTE)) {
                proxy.getPlayer(uuid).ifPresent(this::lifted);
            }
        });
    }

    private void lifted(Player player) {
        push(player);
        if (muteHere(player).isEmpty()) {
            player.sendMessage(text.messages().prefixed(text.messages().languageOf(player),
                    MessageKey.PUNISH_MUTE_ENDED));
        }
    }

    private List<Player> held(Punishment punishment) {
        return proxy.getAllPlayers().stream()
                .filter(player -> service.active().holds(punishment, player.getUniqueId()))
                .toList();
    }

    private void remind(Player player, Punishment mute) {
        UUID uuid = player.getUniqueId();
        if (!notices.remaining(uuid).isZero()) {
            return;
        }
        notices.start(uuid);
        String language = text.messages().languageOf(player);
        player.sendMessage(text.messages().prefixed(language, MessageKey.PUNISH_MUTED_NOTICE,
                Placeholder.unparsed("reason", mute.reason()),
                Placeholder.component("remaining", text.remaining(language, mute, service.clock().instant())),
                Placeholder.unparsed("id", Long.toString(mute.id()))));
    }

    static String firstWord(String command) {
        String trimmed = command.strip();
        int space = trimmed.indexOf(' ');
        return (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    /** Whether {@code alias}, with or without a {@code namespace:}, is one of {@code blocked}. */
    static boolean isMutedCommand(String alias, List<String> blocked) {
        String bare = alias.substring(alias.indexOf(':') + 1);
        return blocked.contains(alias) || blocked.contains(bare);
    }
}
