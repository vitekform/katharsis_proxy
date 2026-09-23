package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.config.AltsConfig;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Finds the other accounts that have played from a player's addresses.
 *
 * <p>Only addresses both accounts used within {@code alts.lookback} count, and
 * trusted addresses never do. A shared address is a hint, not proof: a
 * household, a school or a mobile network puts strangers on one address.</p>
 */
public final class AltDetector {

    /**
     * Another account on the player's addresses.
     *
     * @param uuid      the account
     * @param name      its current name
     * @param sharedIps the addresses they share, most recent first
     * @param lastSeen  when the account was last seen on one of them
     * @param globalBan the network-wide ban on the account itself, if any
     * @param banned    whether any ban is in force against the account
     * @param muted     whether any mute is in force against the account
     */
    public record Alt(UUID uuid, String name, List<String> sharedIps, Instant lastSeen,
                      Optional<Punishment> globalBan, boolean banned, boolean muted) {

        public Alt {
            sharedIps = List.copyOf(sharedIps);
        }
    }

    private final PlayerHistoryRepository history;
    private final PunishmentService service;
    private final Supplier<AltsConfig> config;

    public AltDetector(PlayerHistoryRepository history, PunishmentService service, Supplier<AltsConfig> config) {
        this.history = history;
        this.service = service;
        this.config = config;
    }

    /** The accounts sharing an address with {@code uuid}, most recently seen first. */
    public List<Alt> alts(UUID uuid) throws SQLException {
        Instant since = service.clock().instant().minus(lookback());
        Map<UUID, List<PlayerHistoryRepository.SharedAddress>> byAccount = new LinkedHashMap<>();
        for (PlayerHistoryRepository.SharedAddress shared : history.sharingAddresses(uuid, since)) {
            if (service.usableIp(shared.ip()) != null) {
                byAccount.computeIfAbsent(shared.uuid(), key -> new ArrayList<>()).add(shared);
            }
        }
        List<Alt> alts = new ArrayList<>();
        for (Map.Entry<UUID, List<PlayerHistoryRepository.SharedAddress>> entry : byAccount.entrySet()) {
            UUID alt = entry.getKey();
            List<PlayerHistoryRepository.SharedAddress> rows = entry.getValue();
            List<Punishment> active = service.activeAgainst(alt, null);
            alts.add(new Alt(alt, rows.get(0).name(),
                    rows.stream().map(PlayerHistoryRepository.SharedAddress::ip).distinct().toList(),
                    rows.get(0).lastSeen(),
                    PunishmentService.globalBan(active, alt, true),
                    active.stream().anyMatch(p -> p.type() == PunishmentType.BAN),
                    active.stream().anyMatch(p -> p.type() == PunishmentType.MUTE)));
        }
        return alts;
    }

    private Duration lookback() {
        return DurationParser.parse(config.get().lookback())
                .filter(length -> !length.isPermanent())
                .map(PunishmentLength::duration)
                // "perm" looks back as far as there is anything to see.
                .orElse(Duration.ofDays(365L * 100));
    }
}
