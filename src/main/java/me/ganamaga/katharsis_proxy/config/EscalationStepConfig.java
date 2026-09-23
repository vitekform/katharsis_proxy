package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * One entry of {@code punishments.warns.escalation}: what a player is given
 * automatically when their active warnings reach {@code count}.
 */
@ConfigSerializable
public class EscalationStepConfig {

    private int count = 0;

    /** A template to give; when set, the fields below are ignored. */
    private String template = "";

    /** {@code ban}, {@code mute} or {@code kick}. */
    private String type = "";

    /** {@code perm} or a length such as {@code 1d}. */
    private String duration = "perm";

    private String reason = "";

    /** {@code global}, a server or a group; empty uses the warning's own scope. */
    private String scope = "";

    public int count() {
        return count;
    }

    public String template() {
        return template == null ? "" : template;
    }

    public String type() {
        return type == null ? "" : type;
    }

    public String duration() {
        return duration == null || duration.isBlank() ? "perm" : duration;
    }

    public String reason() {
        return reason == null ? "" : reason;
    }

    public String scope() {
        return scope == null ? "" : scope;
    }
}
