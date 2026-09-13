package me.ganamaga.katharsis_proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.util.CooldownManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends the player to a fixed backend server, rate limited by a per-player
 * cooldown.
 */
public final class ServerSwitchCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Messages messages;
    private final String serverName;
    private final String permission;
    private final CooldownManager cooldowns;

    public ServerSwitchCommand(ProxyServer proxy, Logger logger, Messages messages,
                               String serverName, Duration cooldown) {
        this.proxy = proxy;
        this.logger = logger;
        this.messages = messages;
        this.serverName = serverName;
        this.permission = "katharsis.command." + serverName;
        this.cooldowns = new CooldownManager(cooldown);
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Everyone may use the command unless the node is explicitly denied.
        return invocation.source().getPermissionValue(permission) != Tristate.FALSE;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            messages.send(invocation.source(), MessageKey.PLAYERS_ONLY);
            return;
        }

        String language = messages.languageOf(player);
        UUID playerId = player.getUniqueId();
        Duration remaining = cooldowns.remaining(playerId);
        if (!remaining.isZero()) {
            player.sendMessage(messages.prefixed(language, MessageKey.ON_COOLDOWN,
                    Placeholder.component("time", messages.duration(language, remaining))));
            return;
        }

        Optional<RegisteredServer> target = proxy.getServer(serverName);
        if (target.isEmpty()) {
            player.sendMessage(messages.prefixed(language, MessageKey.UNKNOWN_SERVER, server()));
            logger.warn("{} ran /{} but no server named '{}' is registered on the proxy.",
                    player.getUsername(), serverName, serverName);
            return;
        }

        RegisteredServer server = target.get();
        if (player.getCurrentServer()
                .filter(connection -> connection.getServer().equals(server))
                .isPresent()) {
            player.sendMessage(messages.prefixed(language, MessageKey.ALREADY_CONNECTED, server()));
            return;
        }

        // Start the cooldown before the connection attempt so a player cannot
        // queue up several switches while one is still in flight.
        cooldowns.start(playerId);
        player.sendMessage(messages.prefixed(language, MessageKey.CONNECTING, server()));

        player.createConnectionRequest(server).connect().whenComplete((result, throwable) -> {
            if (throwable != null) {
                cooldowns.clear(playerId);
                player.sendMessage(messages.prefixed(language, MessageKey.CONNECTION_FAILED, server()));
                logger.error("Failed to connect {} to '{}'.", player.getUsername(), serverName, throwable);
                return;
            }
            if (result.isSuccessful()) {
                return;
            }

            // The switch never happened, so do not hold the player to a cooldown.
            cooldowns.clear(playerId);
            result.getReasonComponent().ifPresentOrElse(
                    // A reason from the backend is already localised by whoever sent it.
                    reason -> messages.sendPrefixed(player, reason),
                    () -> player.sendMessage(
                            messages.prefixed(language, MessageKey.CONNECTION_FAILED, server())));
            if (result.getStatus() != ConnectionRequestBuilder.Status.CONNECTION_CANCELLED) {
                logger.warn("Could not connect {} to '{}': {}", player.getUsername(), serverName, result.getStatus());
            }
        });
    }

    /** The {@code <server>} placeholder, inserted unparsed. */
    private TagResolver server() {
        return Placeholder.unparsed("server", serverName);
    }
}
