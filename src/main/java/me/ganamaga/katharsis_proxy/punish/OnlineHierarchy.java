package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;

import java.util.UUID;

/**
 * Protects players who are online and hold {@link PunishPermissions#EXEMPT}.
 * Offline players cannot be asked about their permissions without a
 * permissions plugin to look them up, so they are never protected here.
 */
public final class OnlineHierarchy implements Hierarchy {

    private final ProxyServer proxy;

    public OnlineHierarchy(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public Verdict check(IssuerRef issuer, UUID target) {
        return proxy.getPlayer(target)
                .filter(player -> player.hasPermission(PunishPermissions.EXEMPT))
                .map(player -> Verdict.EXEMPT)
                .orElse(Verdict.ALLOWED);
    }
}
