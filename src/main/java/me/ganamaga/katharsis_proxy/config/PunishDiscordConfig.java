package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code punishments.discord} section: the punishment log channel and the
 * {@code /punish} slash command.
 */
@ConfigSerializable
public class PunishDiscordConfig {

    /** Every punishment issued and lifted is posted here; 0 uses moderation.staff-channel-id. */
    private long logChannelId = 0L;

    /** Show addresses in the log instead of hiding them. */
    private boolean logIps = false;

    private boolean commandsEnabled = false;

    /** A member needs one of these roles to use {@code /punish}. */
    private List<Long> staffRoleIds = new ArrayList<>();

    public long logChannelId() {
        return logChannelId;
    }

    public boolean logIps() {
        return logIps;
    }

    public boolean commandsEnabled() {
        return commandsEnabled;
    }

    public List<Long> staffRoleIds() {
        return staffRoleIds == null ? List.of() : staffRoleIds;
    }
}
