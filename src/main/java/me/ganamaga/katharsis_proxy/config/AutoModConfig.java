package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The {@code punishments.automod} section: warnings given for chat the
 * moderation filters held back.
 */
@ConfigSerializable
public class AutoModConfig {

    private boolean enabled = false;

    /** Shown as the issuer of the warnings. */
    private String issuerName = "AutoMod";

    /** At most one automatic warning per player this often. */
    private String cooldown = "5m";

    /** A template to give instead of a plain warning; empty gives a warning. */
    private String template = "";

    public boolean enabled() {
        return enabled;
    }

    public String issuerName() {
        return issuerName == null || issuerName.isBlank() ? "AutoMod" : issuerName;
    }

    public String cooldown() {
        return cooldown == null || cooldown.isBlank() ? "0s" : cooldown;
    }

    public String template() {
        return template == null ? "" : template;
    }
}
