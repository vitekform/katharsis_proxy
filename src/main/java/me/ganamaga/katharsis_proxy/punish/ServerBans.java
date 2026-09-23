package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.Optional;

/**
 * Answers, from the players' loaded sessions and never the database, whether a
 * player is banned from a server and where they can go instead.
 */
public final class ServerBans {

    private final ProxyServer proxy;
    private final PunishmentService service;
    private final ScopeResolver scopes;
    private final PunishmentText text;

    public ServerBans(ProxyServer proxy, PunishmentService service, ScopeResolver scopes, PunishmentText text) {
        this.proxy = proxy;
        this.service = service;
        this.scopes = scopes;
        this.text = text;
    }

    /** The ban keeping {@code player} off {@code server} longest, if any. */
    public Optional<Punishment> banOn(Player player, String server) {
        return service.active().banOn(player.getUniqueId(), server, scopes, service.clock().instant());
    }

    /**
     * The first server in Velocity's {@code try} list that {@code player} is
     * not banned from, leaving out {@code exclude}.
     */
    public Optional<RegisteredServer> fallback(Player player, Collection<String> exclude) {
        return ServerRouter.firstAllowed(proxy.getConfiguration().getAttemptConnectionOrder(), exclude,
                        server -> banOn(player, server).isPresent())
                .flatMap(proxy::getServer);
    }

    public Component bannedFromServer(Player player, Punishment ban, String server) {
        return text.bannedFromServer(language(player), ban, server, service.clock().instant());
    }

    public Component movedTo(Player player, RegisteredServer server) {
        return text.movedTo(language(player), server.getServerInfo().getName());
    }

    public Component screen(Player player, Punishment ban, String server) {
        return text.serverBanScreen(language(player), ban, server, service.clock().instant());
    }

    private String language(Player player) {
        return text.messages().languageOf(player);
    }
}
