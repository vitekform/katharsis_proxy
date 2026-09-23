package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;

import java.util.Objects;

/**
 * What to lift: one punishment by id, or every active punishment of a type
 * on a target, optionally only on one scope.
 *
 * @param type   the type to lift
 * @param target whose punishments; {@code null} when {@code id} is given
 * @param scope  only punishments on exactly this scope; {@code null} for all
 * @param id     one punishment; {@code null} to go by target
 * @param by     who lifts it
 * @param reason why, or {@code null}
 */
public record RevokeRequest(PunishmentType type, Target target, PunishmentScope scope, Long id,
                            IssuerRef by, String reason) {

    public RevokeRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(by, "by");
        if ((target == null) == (id == null)) {
            throw new IllegalArgumentException("give either a target or an id");
        }
    }

    public static RevokeRequest byTarget(PunishmentType type, Target target, PunishmentScope scope,
                                         IssuerRef by, String reason) {
        return new RevokeRequest(type, target, scope, null, by, reason);
    }

    public static RevokeRequest byId(PunishmentType type, long id, IssuerRef by, String reason) {
        return new RevokeRequest(type, null, null, id, by, reason);
    }
}
