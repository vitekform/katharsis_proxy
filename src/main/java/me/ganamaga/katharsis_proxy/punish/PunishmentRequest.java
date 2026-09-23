package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;

import java.util.Objects;

/**
 * Everything needed to issue a punishment, as a command has worked it out.
 *
 * @param type     what to do
 * @param target   whom to do it to
 * @param ipMode   whether the account, its address, or both are held
 * @param scope    where it applies
 * @param length   how long it lasts; ignored for a kick
 * @param reason   why
 * @param template the template it came from, or {@code null}
 * @param issuer   who issues it
 * @param silent   spare staff the broadcast
 * @param replace  lift an active punishment of the same type and scope first,
 *                 instead of refusing
 * @param parentId the punishment that caused this one, or {@code null}
 */
public record PunishmentRequest(PunishmentType type, Target target, IpMode ipMode, PunishmentScope scope,
                                PunishmentLength length, String reason, String template, IssuerRef issuer,
                                boolean silent, boolean replace, Long parentId) {

    public PunishmentRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ipMode, "ipMode");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(length, "length");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(issuer, "issuer");
    }
}
