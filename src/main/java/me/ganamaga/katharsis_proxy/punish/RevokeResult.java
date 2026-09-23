package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.Punishment;

import java.util.List;

/** What came of asking {@link PunishmentService} to lift punishments. */
public sealed interface RevokeResult {

    /** Lifted, as they now stand. Never empty. */
    record Revoked(List<Punishment> punishments) implements RevokeResult {
    }

    /** Nothing matching was in force. */
    record NothingActive() implements RevokeResult {
    }

    /** The database could not be reached. */
    record Unavailable() implements RevokeResult {
    }
}
