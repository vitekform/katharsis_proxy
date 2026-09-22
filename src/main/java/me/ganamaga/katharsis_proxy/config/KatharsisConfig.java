package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Root of {@code config.yml}. The shipped default file documents every key.
 */
@ConfigSerializable
public class KatharsisConfig {

    private DatabaseConfig database = new DatabaseConfig();
    private MotdConfig motd = new MotdConfig();
    private DiscordConfig discord = new DiscordConfig();
    private ModerationConfig moderation = new ModerationConfig();
    private String defaultLanguage = "en_US";

    /**
     * Whether {@code debug.log} is written in the data directory. On, because
     * the calls recorded there are the ones an admin cannot reconstruct from
     * the console afterwards.
     */
    private boolean debugLog = true;

    public DatabaseConfig database() {
        return database;
    }

    public MotdConfig motd() {
        return motd;
    }

    public DiscordConfig discord() {
        return discord;
    }

    public ModerationConfig moderation() {
        return moderation;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public boolean debugLog() {
        return debugLog;
    }
}
