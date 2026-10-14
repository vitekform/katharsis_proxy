package me.ganamaga.katharsis_neoforge.mute;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is muted on this server, as last told by the proxy. Read from the chat
 * thread and the main thread alike.
 */
public final class MuteRegistry {

    private final Map<UUID, MuteState> mutes = new ConcurrentHashMap<>();

    /** Records what the proxy said; {@code null} means not muted. */
    public void update(UUID uuid, MuteState state) {
        if (state == null) {
            mutes.remove(uuid);
        } else {
            mutes.put(uuid, state);
        }
    }

    /** The mute holding {@code uuid} at {@code nowMillis}, if any. */
    public Optional<MuteState> activeMute(UUID uuid, long nowMillis) {
        MuteState state = mutes.get(uuid);
        if (state == null || !state.isActiveAt(nowMillis)) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public void forget(UUID uuid) {
        mutes.remove(uuid);
    }
}
