package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The {@code updates} section: checking the release system for a newer
 * Katharsis, and fetching it.
 */
@ConfigSerializable
public class UpdatesConfig {

    private boolean enabled = true;

    /** {@code auto}, {@code release} or {@code dev}; see the default config.yml. */
    private String channel = "auto";

    /** Download a newer jar on its own, so the next restart runs it. */
    private boolean autoDownload = true;

    /** Hours between checks; 0 checks only at startup. */
    private int checkIntervalHours = 6;

    /** Tell players with katharsis.update.notify about an update when they join. */
    private boolean notifyStaff = true;

    /** The release system's public root for the project; empty for the default. */
    private String url = "";

    public boolean enabled() {
        return enabled;
    }

    public String channel() {
        return channel == null ? "auto" : channel;
    }

    public boolean autoDownload() {
        return autoDownload;
    }

    public int checkIntervalHours() {
        return Math.max(0, checkIntervalHours);
    }

    public boolean notifyStaff() {
        return notifyStaff;
    }

    public String url() {
        return url == null ? "" : url.strip();
    }
}
