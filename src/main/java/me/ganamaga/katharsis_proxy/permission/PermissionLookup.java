package me.ganamaga.katharsis_proxy.permission;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Answers permission questions about any account, online or not.
 *
 * <p>Methods may block, e.g. to load an offline player's permissions, so none
 * may be called on an event thread.</p>
 */
public interface PermissionLookup {

    /**
     * Whether {@code uuid} holds {@code node}.
     *
     * @return empty if it cannot be told, e.g. the player is offline and there
     *         is no permissions plugin to ask
     */
    Optional<Boolean> has(UUID uuid, String node);

    /**
     * The whole-number meta value {@code key} of {@code uuid}, e.g. a staff
     * rank's weight.
     *
     * @return empty if unset or it cannot be told
     */
    OptionalInt meta(UUID uuid, String key);
}
