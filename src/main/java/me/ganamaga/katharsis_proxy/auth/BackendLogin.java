package me.ganamaga.katharsis_proxy.auth;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.scheduler.ScheduledTask;
import me.ganamaga.katharsis_proxy.config.AuthConfig;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.messaging.AuthChannel;
import me.ganamaga.katharsis_proxy.messaging.AuthCodec;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Logging in on a login server, for players whose client cannot enter the void.
 *
 * <p>A NeoForge client with mods the server must also have refuses to join a
 * vanilla world, which is what the void is. Players heading for a server listed
 * in {@code auth.login-servers} therefore skip the void and join that server
 * straight away. The Katharsis NeoForge mod there holds every player who joins
 * in place, able to do nothing, and says so on {@link AuthChannel}; the proxy
 * answers with {@code unlock} once the player has logged in, which for anyone
 * already logged in is at once.</p>
 *
 * <p>The password never reaches the server: {@code /login} and {@code /register}
 * are taken by the proxy like every other command a player types before logging
 * in, and fed to the same {@link AuthSession} the void uses.</p>
 *
 * <p>Fails closed. A login server that does not say it holds a player within
 * {@link #LOCK_DEADLINE} is taken to be missing the mod, and the player is
 * disconnected rather than left there unchecked.</p>
 */
public final class BackendLogin {

    /** How long a login server has to say it is holding a player. */
    static final Duration LOCK_DEADLINE = Duration.ofSeconds(10);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    /** Null while logging in is off, when everyone the mod holds is let go at once. */
    private final AuthService auth;
    private final Messages messages;
    private final AuthConfig config;
    private final Executor executor;
    private final LoginServers servers;

    /** Players logging in on a login server right now. */
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    /** Servers that have shown they run the mod, so each is logged once. */
    private final Set<String> equipped = ConcurrentHashMap.newKeySet();

    public BackendLogin(Object plugin, ProxyServer proxy, Logger logger, AuthService auth, Messages messages,
                        AuthConfig config, Executor executor, LoginServers servers) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.auth = auth;
        this.messages = messages;
        this.config = config;
        this.executor = executor;
        this.servers = servers;
    }

    /**
     * With logging in off nobody needs holding, but a server running the mod
     * still holds everyone until told otherwise. This lets each of them go.
     */
    public static BackendLogin unlockingEveryone(Object plugin, ProxyServer proxy, Logger logger) {
        return new BackendLogin(plugin, proxy, logger, null, null, null, null, new LoginServers(Set.of()));
    }

    public LoginServers servers() {
        return servers;
    }

    /** Whether {@code player} was routed to a login server, and so skips the void. */
    public boolean logsInOnServer(Player player) {
        return servers.isFirstServerFor(player.getVirtualHost(), proxy.getConfiguration().getForcedHosts(),
                proxy.getConfiguration().getAttemptConnectionOrder());
    }

    /**
     * Something {@code player} typed while not logged in: a chat message, or a
     * command with its slash.
     */
    public void input(Player player, String typed) {
        Held current = held.get(player.getUniqueId());
        if (current != null && current.player == player) {
            current.session.input(typed);
        }
    }

    private boolean loggedIn(Player player) {
        return auth == null || auth.isAuthenticated(player.getUniqueId(), player);
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (loggedIn(player)) {
            return;
        }
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (connection == null) {
            return;
        }
        proxy.getScheduler().buildTask(plugin, () -> checkHeld(player, connection))
                .delay(LOCK_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .schedule();
    }

    /** Disconnects a player the login server never said it was holding. */
    private void checkHeld(Player player, ServerConnection connection) {
        if (!player.isActive() || loggedIn(player)
                || player.getCurrentServer().filter(current -> current == connection).isEmpty()) {
            return;
        }
        Held current = held.get(player.getUniqueId());
        if (current != null && current.player == player) {
            return;
        }
        logger.error("'{}' did not say it was holding {} within {}s, so they were disconnected rather than "
                        + "left there without logging in. Is the Katharsis NeoForge mod installed on it?",
                connection.getServerInfo().getName(), player.getUsername(), LOCK_DEADLINE.toSeconds());
        player.disconnect(messages.render(messages.languageOf(player), MessageKey.AUTH_UNAVAILABLE));
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!AuthChannel.IDENTIFIER.equals(event.getIdentifier())) {
            return;
        }
        // Proxy-internal: never relayed to the other side.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return; // a client speaking our channel, perhaps hoping to unlock itself
        }
        AuthCodec.Incoming message = AuthCodec.decode(event.getData());
        Player player = source.getPlayer();
        String server = source.getServerInfo().getName();
        if (message == null || !message.uuid().equals(player.getUniqueId())) {
            logger.debug("Ignored a malformed or misaddressed '{}' message from '{}'.",
                    AuthChannel.IDENTIFIER.getId(), server);
            return;
        }
        if (!AuthChannel.OP_LOCKED.equals(message.operation())) {
            logger.debug("Ignored unknown operation '{}' on '{}'.", message.operation(),
                    AuthChannel.IDENTIFIER.getId());
            return;
        }
        if (equipped.add(server)) {
            logger.info("'{}' runs the Katharsis NeoForge mod (protocol {}).", server,
                    message.protocol().isEmpty() ? "?" : message.protocol());
        }
        if (!AuthChannel.PROTOCOL.equals(message.protocol())) {
            logger.warn("'{}' speaks protocol '{}' on '{}', this proxy speaks '{}'. Update whichever is older.",
                    server, message.protocol(), AuthChannel.IDENTIFIER.getId(), AuthChannel.PROTOCOL);
        }
        onLocked(player, source);
    }

    private void onLocked(Player player, ServerConnection source) {
        if (loggedIn(player)) {
            unlock(source);
            return;
        }
        String server = source.getServerInfo().getName();
        if (!servers.contains(server)) {
            // The gate keeps players who have not logged in off every other server.
            logger.warn("{} is on '{}' without having logged in, and it is not a login server; disconnecting.",
                    player.getUsername(), server);
            player.disconnect(messages.render(messages.languageOf(player), MessageKey.AUTH_UNAVAILABLE));
            return;
        }
        Held fresh = new Held(player);
        Held previous = held.putIfAbsent(player.getUniqueId(), fresh);
        if (previous != null && previous.player == player) {
            return; // already logging in
        }
        if (previous != null) {
            // A stale entry from an older connection under the same uuid.
            held.put(player.getUniqueId(), fresh);
            previous.close();
        }
        fresh.start();
    }

    private static void unlock(ServerConnection connection) {
        connection.sendPluginMessage(AuthChannel.IDENTIFIER, AuthCodec.encodeUnlock(connection.getPlayer().getUniqueId()));
    }

    /**
     * Only forgets this connection: an old connection closing after its owner
     * reconnected leaves the new one logging in.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        Held current = held.get(player.getUniqueId());
        if (current != null && current.player == player && held.remove(player.getUniqueId(), current)) {
            current.close();
        }
    }

    /** One player logging in on a login server. */
    private final class Held implements AuthSession.Client {

        private final Player player;
        private final AuthSession session;
        private volatile ScheduledTask timeout;

        Held(Player player) {
            this.player = player;
            this.session = new AuthSession(logger, auth, executor, this, player.getUsername(),
                    player.getUniqueId(), player, config.maxAttempts());
        }

        void start() {
            timeout = proxy.getScheduler().buildTask(plugin, session::timeout)
                    .delay(config.loginTimeoutSeconds(), TimeUnit.SECONDS)
                    .schedule();
            session.start();
        }

        void close() {
            session.close();
            ScheduledTask pending = timeout;
            if (pending != null) {
                pending.cancel();
            }
        }

        @Override
        public void prompt(boolean registered) {
            AuthPrompt.show(messages, config, player, registered);
        }

        @Override
        public void message(MessageKey key, TagResolver... placeholders) {
            messages.send(player, key, placeholders);
        }

        @Override
        public void kick(MessageKey key, TagResolver... placeholders) {
            player.disconnect(messages.render(messages.languageOf(player), key, placeholders));
        }

        @Override
        public void pass(MessageKey key) {
            held.remove(player.getUniqueId(), this);
            close();
            player.clearTitle();
            messages.send(player, key);
            // The server they are on now: if that changed, the new one asks again and is let go then.
            player.getCurrentServer().ifPresent(BackendLogin::unlock);
        }
    }
}
