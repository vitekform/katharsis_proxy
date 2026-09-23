package me.ganamaga.katharsis_proxy.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks LuckPerms, which can load the permissions of players who are offline.
 *
 * <p>The only class that names LuckPerms types: it is only constructed once
 * LuckPerms is known to be loaded, so the plugin runs without it.</p>
 *
 * <p>Permissions are checked without contexts, as a staff member's rank does
 * not depend on the server they happen to be on, and an offline player is on
 * none.</p>
 */
public final class LuckPermsLookup implements PermissionLookup {

    /** Loading an offline user reads LuckPerms' storage; do not wait on it for ever. */
    private static final long LOAD_TIMEOUT_SECONDS = 5L;

    private final Logger logger;
    private final LuckPerms luckPerms;

    public LuckPermsLookup(Logger logger) {
        this.logger = logger;
        this.luckPerms = LuckPermsProvider.get();
    }

    @Override
    public Optional<Boolean> has(UUID uuid, String node) {
        return user(uuid).map(user -> user.getCachedData()
                .getPermissionData(QueryOptions.nonContextual())
                .checkPermission(node)
                .asBoolean());
    }

    @Override
    public OptionalInt meta(UUID uuid, String key) {
        Optional<Integer> value = user(uuid).flatMap(user -> user.getCachedData()
                .getMetaData(QueryOptions.nonContextual())
                .getMetaValue(key, raw -> {
                    try {
                        return Integer.parseInt(raw.strip());
                    } catch (NumberFormatException e) {
                        logger.warn("LuckPerms meta '{}' of {} is '{}', not a whole number.", key, uuid, raw);
                        return null;
                    }
                }));
        return value.map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    private Optional<User> user(UUID uuid) {
        User loaded = luckPerms.getUserManager().getUser(uuid);
        if (loaded != null) {
            return Optional.of(loaded);
        }
        try {
            return Optional.ofNullable(luckPerms.getUserManager().loadUser(uuid)
                    .get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            logger.warn("Could not load the LuckPerms data of {}.", uuid, e);
            return Optional.empty();
        }
    }
}
