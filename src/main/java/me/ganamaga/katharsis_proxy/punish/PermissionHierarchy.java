package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.permission.PermissionLookup;

import java.util.UUID;

/**
 * Decides from permissions who may punish whom.
 *
 * <ul>
 *   <li>Nobody may punish a holder of {@link PunishPermissions#EXEMPT}.</li>
 *   <li>Staff may not punish anyone whose {@link #WEIGHT_META} is at or above
 *       their own, unless they hold {@link PunishPermissions#OVERRIDE}. Without
 *       the meta a player weighs 0, so while no weights are set up, anyone may
 *       punish anyone.</li>
 *   <li>The console always outranks.</li>
 * </ul>
 */
public final class PermissionHierarchy implements Hierarchy {

    /** LuckPerms meta holding a player's rank for punishments, e.g. {@code /lp group mod meta set katharsis-punish-weight 10}. */
    public static final String WEIGHT_META = "katharsis-punish-weight";

    private final PermissionLookup permissions;

    public PermissionHierarchy(PermissionLookup permissions) {
        this.permissions = permissions;
    }

    @Override
    public Verdict check(IssuerRef issuer, UUID target) {
        if (permissions.has(target, PunishPermissions.EXEMPT).orElse(false)) {
            return Verdict.EXEMPT;
        }
        if (issuer.uuid() == null || issuer.uuid().equals(target)) {
            return Verdict.ALLOWED;
        }
        if (permissions.has(issuer.uuid(), PunishPermissions.OVERRIDE).orElse(false)) {
            return Verdict.ALLOWED;
        }
        int targetWeight = permissions.meta(target, WEIGHT_META).orElse(0);
        if (targetWeight <= 0) {
            return Verdict.ALLOWED;
        }
        int issuerWeight = permissions.meta(issuer.uuid(), WEIGHT_META).orElse(0);
        return targetWeight >= issuerWeight ? Verdict.OUTRANKED : Verdict.ALLOWED;
    }
}
