package me.ganamaga.katharsis_proxy.motd;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import me.ganamaga.katharsis_proxy.config.MotdConfig;
import me.ganamaga.katharsis_proxy.config.MotdFallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Answers the server list ping with a banner MOTD, falling back to plain text
 * for clients that cannot render one.
 *
 * <p>The configuration is read through a {@link Supplier} rather than captured,
 * so a reload takes effect without re-registering the listener.</p>
 */
public final class MotdPingListener {

    private final Supplier<MotdConfig> config;
    private final MotdLibrary library;

    public MotdPingListener(Supplier<MotdConfig> config, MotdLibrary library) {
        this.config = config;
        this.library = library;
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        MotdConfig motd = config.get();
        if (!motd.enabled()) {
            return;
        }

        ServerPing ping = event.getPing();
        ServerPing.Builder builder = ping.asBuilder();

        applyDescription(builder, motd, event.getConnection().getProtocolVersion().getProtocol());
        applyVersion(builder, ping.getVersion(), motd);
        applyPlayerCount(builder, motd);

        event.setPing(builder.build());
    }

    /**
     * Picks the banner for this client, or the fallback when it is too old to
     * render one. Under {@link MotdFallback#PROXY} the description is left
     * untouched so the MOTD from velocity.toml shows through.
     */
    private void applyDescription(ServerPing.Builder builder, MotdConfig motd, int protocol) {
        Optional<Component> banner = motd.supportsBanner(protocol)
                ? library.select(motd.mode())
                : Optional.empty();

        if (banner.isPresent()) {
            builder.description(banner.get());
        } else if (motd.fallback() == MotdFallback.CONFIG) {
            builder.description(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(motd.fallbackMotd()));
        }
    }

    /**
     * Rewrites the version line, which the client shows in place of the ping
     * bars when it cannot join.
     */
    private void applyVersion(ServerPing.Builder builder, ServerPing.Version version, MotdConfig motd) {
        if (!motd.rewritesVersion()) {
            return;
        }

        String name = version.getName();
        if (motd.overrideVersionMessage()) {
            // The version line is a raw string the client renders with section
            // codes, so the ampersand form used everywhere else in config.yml
            // has to be translated rather than passed through.
            name = LegacyComponentSerializer.legacySection().serialize(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(motd.customVersionMessage()));
        }

        int protocol = motd.alwaysOutOfDate() ? motd.outOfDateProtocol() : version.getProtocol();
        builder.version(new ServerPing.Version(protocol, name));
    }

    private void applyPlayerCount(ServerPing.Builder builder, MotdConfig motd) {
        if (motd.hidePlayerCount()) {
            builder.nullPlayers();
        } else if (motd.fakePlayerCount()) {
            builder.maximumPlayers(motd.maxPlayers());
            builder.onlinePlayers(motd.fakeOnlinePlayers());
        }
    }
}
