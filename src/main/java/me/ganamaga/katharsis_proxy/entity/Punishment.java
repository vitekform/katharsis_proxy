package me.ganamaga.katharsis_proxy.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One punishment, as issued, and its revocation if it has been lifted.
 *
 * <p>A punishment targets an account, an address, or both: a default
 * {@code /ban} fills in both, so the one row keeps the player and their address
 * out and one {@code /unban} lets both back in. {@code targetIp} is only set
 * when the address part is enforced.</p>
 *
 * <p>Rows are never deleted. Lifting a punishment fills in its
 * {@link Revocation}, so the history still shows it.</p>
 *
 * @param id          the row id; {@code 0} until the punishment is stored
 * @param type        what the punishment does
 * @param targetUuid  the punished account; {@code null} for an address-only punishment
 * @param targetName  the account's name when punished, or the address; for display
 * @param targetIp    the punished address; {@code null} when only the account is punished
 * @param scope       where the punishment applies
 * @param reason      why, as typed or taken from a template
 * @param template    the template it was issued from, {@code null} if typed by hand
 * @param issuer      who issued it
 * @param silent      whether staff were spared the broadcast
 * @param parentId    the punishment that caused this one (an escalating warning,
 *                    a banned account being evaded); {@code null} otherwise
 * @param createdAt   when it was issued
 * @param expiresAt   when it runs out; {@code null} if it never does
 * @param revocation  how it was lifted; {@code null} while it has not been
 */
public record Punishment(long id, PunishmentType type, UUID targetUuid, String targetName, String targetIp,
                         PunishmentScope scope, String reason, String template, IssuerRef issuer, boolean silent,
                         Long parentId, Instant createdAt, Instant expiresAt, Revocation revocation) {

    /** Width of the reason columns. */
    public static final int REASON_MAX_LENGTH = 512;
    /** Width of the {@code target_name} column; wide enough for an IPv6 address. */
    public static final int TARGET_NAME_MAX_LENGTH = 64;
    /** Width of the {@code target_ip} column: the longest textual IPv6 address. */
    public static final int IP_MAX_LENGTH = 45;
    /** Width of the {@code template} column. */
    public static final int TEMPLATE_MAX_LENGTH = 64;

    public Punishment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(createdAt, "createdAt");
        if (targetUuid == null && targetIp == null) {
            throw new IllegalArgumentException("a punishment needs an account, an address, or both");
        }
        checkLength("targetName", targetName, TARGET_NAME_MAX_LENGTH);
        checkLength("targetIp", targetIp, IP_MAX_LENGTH);
        checkLength("reason", reason, REASON_MAX_LENGTH);
        checkLength("template", template, TEMPLATE_MAX_LENGTH);
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("a punishment must expire after it is issued");
        }
        if (!type.isLasting() && expiresAt != null) {
            throw new IllegalArgumentException(type + " does not last, so it cannot expire");
        }
    }

    private static void checkLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
    }

    /** A copy carrying the id the database gave it. */
    public Punishment withId(long newId) {
        return new Punishment(newId, type, targetUuid, targetName, targetIp, scope, reason, template, issuer,
                silent, parentId, createdAt, expiresAt, revocation);
    }

    /** A copy lifted by {@code newRevocation}. */
    public Punishment withRevocation(Revocation newRevocation) {
        return new Punishment(id, type, targetUuid, targetName, targetIp, scope, reason, template, issuer,
                silent, parentId, createdAt, expiresAt, newRevocation);
    }

    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isRevoked() {
        return revocation != null;
    }

    /** Whether it is in force at {@code now}: lasting, not lifted, and not run out. */
    public boolean isActiveAt(Instant now) {
        return type.isLasting() && revocation == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * How a punishment was lifted.
     *
     * @param at     when
     * @param by     who lifted it
     * @param reason why, {@code null} if none was given
     */
    public record Revocation(Instant at, IssuerRef by, String reason) {

        public Revocation {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(by, "by");
            checkLength("reason", reason, REASON_MAX_LENGTH);
        }
    }
}
