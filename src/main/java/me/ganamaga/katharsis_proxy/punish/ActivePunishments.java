package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The punishments in force against each player online, so that server
 * switches, commands and chat are checked without touching the database.
 *
 * <p>Loaded at login, kept up to date by {@link PunishmentService} as
 * punishments are issued and lifted, and dropped at disconnect. Each entry
 * belongs to the connection that loaded it, the way {@code AuthService} tracks
 * logins: a second connection under the same uuid, turned away as a duplicate,
 * cannot drop the entry of the player who is actually on.</p>
 */
public final class ActivePunishments {

    /**
     * One player's session.
     *
     * @param owner      the connection it belongs to
     * @param ip         the address the player joined from, or {@code null}
     * @param active     what was in force when last loaded or changed
     * @param generation {@link PunishmentService#generation()} before it was loaded
     * @param exempt     whether the player is exempt from punishment, and so
     *                   not held by punishments on an address they share
     */
    public record Session(Object owner, String ip, List<Punishment> active, long generation, boolean exempt) {

        public Session {
            Objects.requireNonNull(owner, "owner");
            active = List.copyOf(active);
        }

        public Session(Object owner, String ip, List<Punishment> active, long generation) {
            this(owner, ip, active, generation, false);
        }

        Session with(List<Punishment> newActive) {
            return new Session(owner, ip, newActive, generation, exempt);
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public void put(UUID uuid, Session session) {
        sessions.put(uuid, session);
    }

    public Optional<Session> session(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    /** Drops the entry of {@code uuid} if it still belongs to {@code owner}. */
    public void remove(UUID uuid, Object owner) {
        sessions.computeIfPresent(uuid, (key, session) -> session.owner() == owner ? null : session);
    }

    public void clear() {
        sessions.clear();
    }

    /** What of {@code type} is in force against {@code uuid} at {@code now}, newest first. */
    public List<Punishment> active(UUID uuid, PunishmentType type, Instant now) {
        Session session = sessions.get(uuid);
        if (session == null) {
            return List.of();
        }
        return session.active().stream()
                .filter(p -> p.type() == type && p.isActiveAt(now))
                .toList();
    }

    /**
     * Adds a new punishment to every online player it holds: the account it
     * names, and everyone on the address it names.
     *
     * @return the players it was added to
     */
    List<UUID> added(Punishment punishment) {
        if (!punishment.type().isLasting()) {
            return List.of();
        }
        List<UUID> affected = new ArrayList<>();
        sessions.replaceAll((uuid, session) -> {
            if (!holds(punishment, uuid, session.exempt() ? null : session.ip())) {
                return session;
            }
            affected.add(uuid);
            List<Punishment> active = new ArrayList<>(session.active().size() + 1);
            active.add(punishment);
            active.addAll(session.active());
            return session.with(active);
        });
        return affected;
    }

    /** Removes a lifted punishment from everyone holding it. */
    void revoked(long id) {
        sessions.replaceAll((uuid, session) -> session.active().stream().anyMatch(p -> p.id() == id)
                ? session.with(session.active().stream().filter(p -> p.id() != id).toList())
                : session);
    }

    /**
     * Drops punishments that have run out.
     *
     * @return per player, the punishments that ran out; players with none are left out
     */
    public Map<UUID, List<Punishment>> evictExpired(Instant now) {
        Map<UUID, List<Punishment>> expired = new LinkedHashMap<>();
        sessions.replaceAll((uuid, session) -> {
            List<Punishment> gone = session.active().stream().filter(p -> !p.isActiveAt(now)).toList();
            if (gone.isEmpty()) {
                return session;
            }
            expired.put(uuid, gone);
            return session.with(session.active().stream().filter(p -> p.isActiveAt(now)).toList());
        });
        return expired;
    }

    /**
     * The ban in force against {@code uuid} on {@code server} at {@code now}
     * that keeps them off longest, if any.
     */
    public Optional<Punishment> banOn(UUID uuid, String server, ScopeResolver scopes, Instant now) {
        return active(uuid, PunishmentType.BAN, now).stream()
                .filter(ban -> scopes.appliesTo(ban.scope(), server))
                .max(PunishmentService.LONGEST);
    }

    static boolean holds(Punishment punishment, UUID uuid, String ip) {
        return uuid.equals(punishment.targetUuid())
                || (ip != null && ip.equals(punishment.targetIp()));
    }
}
