package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of {@code punishments.templates}: a punishment staff give by name,
 * e.g. {@code /ban Steve #hacking}, lasting longer each time the same player
 * earns it again.
 */
@ConfigSerializable
public class PunishTemplateConfig {

    /** {@code ban}, {@code mute}, {@code warn} or {@code kick}. */
    private String type = "ban";

    private String reason = "";

    /** {@code global}, a server or a group; empty uses the command's scope. */
    private String scope = "";

    /** Lengths for the 1st, 2nd, ... offence; the last repeats. Empty is permanent. */
    private List<String> ladder = new ArrayList<>();

    public String type() {
        return type == null ? "" : type;
    }

    public String reason() {
        return reason == null ? "" : reason;
    }

    public String scope() {
        return scope == null ? "" : scope;
    }

    public List<String> ladder() {
        return ladder == null ? List.of() : ladder;
    }
}
