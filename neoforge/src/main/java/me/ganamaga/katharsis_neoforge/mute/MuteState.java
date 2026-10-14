package me.ganamaga.katharsis_neoforge.mute;

import me.ganamaga.katharsis_neoforge.protocol.PunishChannel;

import java.util.List;
import java.util.Locale;

/**
 * A player's mute on this server, as the proxy described it.
 *
 * @param expiresAt       when it ends, in epoch milliseconds, or {@link PunishChannel#PERMANENT}
 * @param punishmentId    the mute's id on the proxy
 * @param blockedCommands commands it blocks, lower-case, without a slash
 */
public record MuteState(long expiresAt, long punishmentId, List<String> blockedCommands) {

    public MuteState {
        blockedCommands = List.copyOf(blockedCommands);
    }

    /** Whether it still holds at {@code nowMillis}; it ends on its own even if the proxy never says so. */
    public boolean isActiveAt(long nowMillis) {
        return expiresAt == PunishChannel.PERMANENT || nowMillis < expiresAt;
    }

    /**
     * Whether {@code commandLine}, as typed after the slash, starts with one
     * of the blocked commands, with or without a {@code namespace:}.
     */
    public boolean blocks(String commandLine) {
        String line = commandLine.strip();
        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        String bare = label.substring(label.indexOf(':') + 1);
        return blockedCommands.contains(label) || blockedCommands.contains(bare);
    }
}
