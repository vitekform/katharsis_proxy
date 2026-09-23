package me.ganamaga.katharsis_proxy.permission;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Asks Velocity, which only knows about players who are online. Used without
 * LuckPerms; offline players and meta values cannot be told.
 */
public final class OnlinePermissionLookup implements PermissionLookup {

    private final ProxyServer proxy;

    public OnlinePermissionLookup(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public Optional<Boolean> has(UUID uuid, String node) {
        return proxy.getPlayer(uuid).map(player -> player.getPermissionValue(node) == Tristate.TRUE);
    }

    @Override
    public OptionalInt meta(UUID uuid, String key) {
        return OptionalInt.empty();
    }
}
