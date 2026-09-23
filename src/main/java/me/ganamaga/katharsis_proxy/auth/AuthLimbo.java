package me.ganamaga.katharsis_proxy.auth;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.Katharsis_proxy;
import me.ganamaga.katharsis_proxy.config.AuthConfig;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The empty world players without a Mojang account log in from.
 *
 * <p>It is a LimboAPI virtual world: it lives inside the proxy, and a player in
 * it has not touched any backend. LimboAPI holds every new connection here
 * until the session lets it go, and only then does Velocity pick the first
 * server.</p>
 *
 * <p>This is the only class that names LimboAPI types, and loading it at all,
 * even to call a static method, fails without LimboAPI installed. Check for
 * the plugin through Velocity's plugin manager, without touching this class,
 * first.</p>
 */
public final class AuthLimbo {

    /** High above nothing: there are no blocks, and falling is switched off. */
    private static final double SPAWN_Y = 100.0d;

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final AuthService auth;
    private final Messages messages;
    private final AuthConfig config;
    private final Executor executor;

    private Limbo limbo;

    public AuthLimbo(Object plugin, ProxyServer proxy, Logger logger, AuthService auth, Messages messages,
                     AuthConfig config, Executor executor) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.auth = auth;
        this.messages = messages;
        this.config = config;
        this.executor = executor;
    }

    /** Builds the world and starts holding new players in it. */
    public void start() {
        LimboFactory factory = (LimboFactory) proxy.getPluginManager().getPlugin(Katharsis_proxy.LIMBOAPI_ID)
                .flatMap(PluginContainer::getInstance)
                .orElseThrow(() -> new IllegalStateException("LimboAPI is not loaded"));

        // The End's sky is plain and dark, and no chunk is ever filled in.
        VirtualWorld world = factory.createVirtualWorld(Dimension.THE_END, 0.0d, SPAWN_Y, 0.0d, 0.0f, 0.0f);
        limbo = factory.createLimbo(world)
                .setName("KatharsisLogin")
                .setGameMode(GameMode.ADVENTURE)
                .setWorldTime(6000L)
                .setReducedDebugInfo(true)
                // Declared so 1.19+ clients send them as commands and can tab-complete them.
                .registerCommand(new LimboCommandMeta(List.of("login", "l")))
                .registerCommand(new LimboCommandMeta(List.of("register", "reg")));
        proxy.getEventManager().register(plugin, this);
    }

    public void dispose() {
        if (limbo != null) {
            limbo.dispose();
            limbo = null;
        }
    }

    @Subscribe
    public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
        Player player = event.getPlayer();
        // Mojang already vouched for them. Checked here as well as on LoginEvent
        // so the order LimboAPI fires its event in does not matter.
        if (player.isOnlineMode()) {
            auth.markAuthenticated(player.getUniqueId(), player);
        }
        if (auth.isAuthenticated(player.getUniqueId(), player)) {
            return;
        }
        event.addOnJoinCallback(() -> limbo.spawnPlayer(player, new Handler(player)));
    }

    /** Connects one player's {@link AuthSession} to LimboAPI. */
    private final class Handler implements LimboSessionHandler, AuthSession.Client {

        private final Player player;
        private final AuthSession session;
        private volatile LimboPlayer limboPlayer;
        private volatile ScheduledFuture<?> timeout;

        Handler(Player player) {
            this.player = player;
            this.session = new AuthSession(logger, auth, executor, this, player.getUsername(),
                    player.getUniqueId(), player, config.maxAttempts());
        }

        @Override
        public void onSpawn(Limbo server, LimboPlayer limboPlayer) {
            this.limboPlayer = limboPlayer;
            limboPlayer.disableFalling();
            timeout = limboPlayer.getScheduledExecutor()
                    .schedule(session::timeout, config.loginTimeoutSeconds(), TimeUnit.SECONDS);
            session.start();
        }

        @Override
        public void onChat(String chat) {
            session.input(chat);
        }

        @Override
        public void onDisconnect() {
            session.close();
            ScheduledFuture<?> pending = timeout;
            if (pending != null) {
                pending.cancel(false);
            }
        }

        @Override
        public void prompt(boolean registered) {
            String language = messages.languageOf(player);
            // Held for the whole login window; it goes when they leave the void.
            Title.Times times = Title.Times.times(Duration.ofMillis(500),
                    Duration.ofSeconds(config.loginTimeoutSeconds()), Duration.ofMillis(500));
            player.showTitle(Title.title(
                    messages.render(language, MessageKey.AUTH_TITLE),
                    messages.render(language, registered
                            ? MessageKey.AUTH_SUBTITLE_LOGIN
                            : MessageKey.AUTH_SUBTITLE_REGISTER),
                    times));
            message(registered ? MessageKey.AUTH_PROMPT_LOGIN : MessageKey.AUTH_PROMPT_REGISTER);
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
            ScheduledFuture<?> pending = timeout;
            if (pending != null) {
                pending.cancel(false);
            }
            LimboPlayer current = limboPlayer;
            // On the connection's own thread, like everything else LimboAPI does with it.
            current.getScheduledExecutor().execute(() -> {
                player.clearTitle();
                messages.send(player, key);
                current.disconnect();
            });
        }
    }
}
