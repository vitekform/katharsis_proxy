package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Issues and lifts punishments, and keeps {@link ActivePunishments} in step.
 *
 * <p>Every method blocks on the database, so none may run on an event thread.
 * Nothing here touches Velocity: enforcing, announcing and logging are left to
 * the {@link PunishmentObserver}s, told once a change is stored.</p>
 */
public final class PunishmentService {

    /** Reason stored on a punishment lifted because a new one replaced it. */
    static final String REPLACED_REASON = "Replaced";

    private final Logger logger;
    private final PunishmentRepository punishments;
    private final ActivePunishments active;
    private final Hierarchy hierarchy;
    private final Predicate<String> trustedIp;
    private final Clock clock;
    private final List<PunishmentObserver> observers = new CopyOnWriteArrayList<>();

    /**
     * Bumped after every change is stored. A login loads its punishments after
     * reading this, so a change it could have missed shows up as a different
     * number afterwards.
     */
    private final AtomicLong generation = new AtomicLong();

    public PunishmentService(Logger logger, PunishmentRepository punishments, ActivePunishments active,
                             Hierarchy hierarchy, Predicate<String> trustedIp, Clock clock) {
        this.logger = logger;
        this.punishments = punishments;
        this.active = active;
        this.hierarchy = hierarchy;
        this.trustedIp = trustedIp;
        this.clock = clock;
    }

    public void addObserver(PunishmentObserver observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public ActivePunishments active() {
        return active;
    }

    public Clock clock() {
        return clock;
    }

    public long generation() {
        return generation.get();
    }

    /** {@code ip}, unless it is missing or one many players share innocently. */
    public String usableIp(String ip) {
        return ip == null || trustedIp.test(ip) ? null : ip;
    }

    /** Issues a punishment, enforces it, and tells the observers. */
    public IssueResult issue(PunishmentRequest request) {
        Target target = request.target();
        PunishmentType type = request.type();
        // Warnings and kicks are about what an account did, never an address.
        IpMode mode = type == PunishmentType.WARN || type == PunishmentType.KICK ? IpMode.ACCOUNT : request.ipMode();
        UUID uuid = mode == IpMode.IP_ONLY ? null : target.uuid();
        String ip = mode == IpMode.ACCOUNT && !target.isAddress() ? null : usableIp(target.ip());
        if (uuid == null && ip == null) {
            return new IssueResult.NoAddress();
        }
        boolean addressSkipped = mode == IpMode.ACCOUNT_AND_IP && uuid != null && ip == null;

        if (uuid != null && request.issuer().isPerson()) {
            switch (hierarchy.check(request.issuer(), uuid)) {
                case EXEMPT -> {
                    return new IssueResult.Exempt();
                }
                case OUTRANKED -> {
                    return new IssueResult.Outranked();
                }
                case ALLOWED -> {
                }
            }
        }

        Instant now = clock.instant();
        try {
            int replaced = 0;
            if (type == PunishmentType.BAN || type == PunishmentType.MUTE) {
                List<Punishment> existing = sameKind(type, uuid, ip, request.scope(), now);
                if (!existing.isEmpty()) {
                    if (!request.replace()) {
                        return new IssueResult.AlreadyActive(existing.get(0));
                    }
                    Punishment.Revocation revocation =
                            new Punishment.Revocation(now, request.issuer(), REPLACED_REASON);
                    replaced = revokeAll(existing, revocation).size();
                }
            }
            Punishment stored = punishments.insert(new Punishment(0, type, uuid, target.name(), ip,
                    request.scope(), truncate(request.reason()), request.template(), request.issuer(),
                    request.silent(), request.parentId(), now,
                    type.isLasting() ? request.length().expiresAt(now) : null, null));
            generation.incrementAndGet();
            active.added(stored);
            logger.info("{} issued {} #{} against {} on {} ({}): {}", request.issuer().name(), type, stored.id(),
                    describe(stored), stored.scope().describe(),
                    stored.isPermanent() ? "permanent" : "until " + stored.expiresAt(), stored.reason());
            notify(observer -> observer.issued(stored));
            return new IssueResult.Issued(stored, addressSkipped, replaced);
        } catch (SQLException e) {
            logger.error("Could not issue a {} against {}.", type, target.name(), e);
            return new IssueResult.Unavailable();
        }
    }

    /**
     * Active punishments of {@code type} on exactly {@code scope} against the
     * same target: the account if there is one, else the address.
     */
    private List<Punishment> sameKind(PunishmentType type, UUID uuid, String ip, PunishmentScope scope,
                                      Instant now) throws SQLException {
        List<Punishment> found = uuid != null
                ? punishments.findActive(uuid, null, now)
                : punishments.findActive(null, ip, now);
        return found.stream()
                .filter(p -> p.type() == type && p.scope().equals(scope))
                .filter(p -> uuid != null || p.targetUuid() == null)
                .toList();
    }

    /** Lifts punishments by id or by target, and tells the observers. */
    public RevokeResult revoke(RevokeRequest request) {
        Instant now = clock.instant();
        try {
            List<Punishment> matching;
            if (request.id() != null) {
                matching = punishments.find(request.id())
                        .filter(p -> p.type() == request.type() && p.isActiveAt(now))
                        .map(List::of)
                        .orElse(List.of());
            } else {
                Target target = request.target();
                matching = (target.isAddress()
                        ? punishments.findActive(null, target.ip(), now)
                        : punishments.findActive(target.uuid(), null, now)).stream()
                        .filter(p -> p.type() == request.type())
                        .filter(p -> request.scope() == null || p.scope().equals(request.scope()))
                        .limit(request.newestOnly() ? 1 : Long.MAX_VALUE)
                        .toList();
            }
            List<Punishment> revoked = revokeAll(matching,
                    new Punishment.Revocation(now, request.by(), truncate(request.reason())));
            return revoked.isEmpty() ? new RevokeResult.NothingActive() : new RevokeResult.Revoked(revoked);
        } catch (SQLException e) {
            logger.error("Could not lift {} punishments.", request.type(), e);
            return new RevokeResult.Unavailable();
        }
    }

    private List<Punishment> revokeAll(List<Punishment> matching, Punishment.Revocation revocation)
            throws SQLException {
        List<Punishment> revoked = new ArrayList<>();
        for (Punishment punishment : matching) {
            // Someone else may have lifted it in the meantime; only report ours.
            if (punishments.revoke(punishment.id(), revocation)) {
                revoked.add(punishment.withRevocation(revocation));
            }
        }
        if (!revoked.isEmpty()) {
            generation.incrementAndGet();
        }
        for (Punishment punishment : revoked) {
            active.revoked(punishment.id());
            logger.info("{} lifted {} #{} against {}.", revocation.by().name(), punishment.type(),
                    punishment.id(), describe(punishment));
            notify(observer -> observer.revoked(punishment));
        }
        return revoked;
    }

    /**
     * Everything in force against the account {@code uuid} or the address
     * {@code ip}, newest first. A trusted address is not looked at.
     */
    public List<Punishment> activeAgainst(UUID uuid, String ip) throws SQLException {
        String usable = usableIp(ip);
        if (uuid == null && usable == null) {
            return List.of();
        }
        return punishments.findActive(uuid, usable, clock.instant());
    }

    /**
     * The network-wide ban in {@code active} that keeps its target out
     * longest, if any.
     *
     * @param ignoreAddressOnly skip bans that hold the address but not this
     *                          account {@code uuid}, for players exempt from punishment
     */
    public static Optional<Punishment> globalBan(List<Punishment> active, UUID uuid, boolean ignoreAddressOnly) {
        return active.stream()
                .filter(p -> p.type() == PunishmentType.BAN && p.scope().isGlobal())
                .filter(p -> !ignoreAddressOnly || (uuid != null && uuid.equals(p.targetUuid())))
                .max(LONGEST);
    }

    /** Permanent first, then by the later end. */
    public static final Comparator<Punishment> LONGEST = Comparator.comparing(
            Punishment::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()));

    /** The warnings in force against the account {@code uuid} itself, newest first. */
    public List<Punishment> activeWarns(UUID uuid) throws SQLException {
        return punishments.findActive(uuid, null, clock.instant()).stream()
                .filter(p -> p.type() == PunishmentType.WARN)
                .toList();
    }

    /**
     * How many unrevoked punishments the account {@code uuid} has had from
     * {@code template}, which picks the step of its ladder.
     */
    public int templateCount(UUID uuid, String template) throws SQLException {
        return uuid == null ? 0 : punishments.countByTemplate(uuid, template);
    }

    /** One page of what was ever issued against {@code target}, newest first. */
    public List<Punishment> history(Target target, int limit, int offset) throws SQLException {
        return target.isAddress()
                ? punishments.findHistory(null, target.ip(), limit, offset)
                : punishments.findHistory(target.uuid(), null, limit, offset);
    }

    public int countHistory(Target target) throws SQLException {
        return target.isAddress()
                ? punishments.countHistory(null, target.ip())
                : punishments.countHistory(target.uuid(), null);
    }

    private void notify(Consumer<PunishmentObserver> call) {
        for (PunishmentObserver observer : observers) {
            try {
                call.accept(observer);
            } catch (RuntimeException e) {
                logger.error("A punishment observer failed.", e);
            }
        }
    }

    private static String describe(Punishment punishment) {
        if (punishment.targetUuid() == null) {
            return punishment.targetIp();
        }
        return punishment.targetName() + " (" + punishment.targetUuid() + ")"
                + (punishment.targetIp() == null ? "" : " and their address");
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= Punishment.REASON_MAX_LENGTH ? reason
                : reason.substring(0, Punishment.REASON_MAX_LENGTH);
    }
}
