package me.ganamaga.katharsis_proxy.auth;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.sql.SQLException;

/**
 * Keeps players who have not logged in away from everything.
 *
 * <p>Before login it decides how each name joins: through Mojang, or offline to
 * log in with a password in {@link AuthLimbo}. Afterwards it refuses every
 * command and every server connection until the player has logged in. The void
 * already keeps them from both; this is the second lock, so a player who ends
 * up outside it for any reason is still stopped.</p>
 */
public final class AuthGateListener {

    private final ProxyServer proxy;
    private final Logger logger;
    private final AuthService auth;
    private final Messages messages;

    /** Without the void there is nowhere to log in, so password players cannot join. */
    private final boolean voidAvailable;

    public AuthGateListener(ProxyServer proxy, Logger logger, AuthService auth, Messages messages,
                            boolean voidAvailable) {
        this.proxy = proxy;
        this.logger = logger;
        this.auth = auth;
        this.messages = messages;
        this.voidAvailable = voidAvailable;
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onPreLogin(PreLoginEvent event) {
        // Already refused by someone else, or Mojang checks everyone anyway.
        if (!event.getResult().isAllowed() || proxy.getConfiguration().isOnlineMode()) {
            return null;
        }
        String username = event.getUsername();
        // Off the event thread: this reads the database and may ask Mojang.
        return EventTask.async(() -> {
            switch (auth.decide(username)) {
                case AuthService.JoinDecision.Premium ignored ->
                        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                case AuthService.JoinDecision.Password ignored -> {
                    if (!voidAvailable) {
                        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                                render(MessageKey.AUTH_UNAVAILABLE)));
                    }
                }
                case AuthService.JoinDecision.WrongCase wrongCase ->
                        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                                render(MessageKey.AUTH_KICK_WRONG_CASE,
                                        Placeholder.unparsed("name", wrongCase.registeredName()))));
                case AuthService.JoinDecision.Unavailable ignored ->
                        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                                render(MessageKey.AUTH_UNAVAILABLE)));
            }
        });
    }

    /** Mojang logged them in, so they are in without a password. */
    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (!event.getResult().isAllowed() || !player.isOnlineMode()) {
            return null;
        }
        auth.markAuthenticated(player.getUniqueId(), player);
        return EventTask.async(() -> {
            try {
                auth.recordPremium(player.getUsername(), player.getUniqueId());
            } catch (SQLException | RuntimeException e) {
                // They are in either way; the name is simply looked up at Mojang again next time.
                logger.error("Could not record the premium account of {} ({}).",
                        player.getUsername(), player.getUniqueId(), e);
            }
        });
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onCommand(CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player player && !auth.isAuthenticated(player.getUniqueId(), player)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        if (!auth.isAuthenticated(player.getUniqueId(), player)) {
            logger.warn("Kept {} off '{}': they have not logged in.",
                    player.getUsername(), event.getOriginalServer().getServerInfo().getName());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    /**
     * Only forgets this connection: someone turned away for joining under a name
     * already online, or an old connection closing after its owner reconnected,
     * leaves the player who is actually on alone.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        auth.forget(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    private Component render(MessageKey key, TagResolver... placeholders) {
        return messages.render(messages.registry().defaultLanguage(), key, placeholders);
    }
}
