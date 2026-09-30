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
    private AuthConfig auth = new AuthConfig();
    private PunishmentsConfig punishments = new PunishmentsConfig();
    private UpdatesConfig updates = new UpdatesConfig();
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

    public AuthConfig auth() {
        return auth == null ? new AuthConfig() : auth;
    }

    public PunishmentsConfig punishments() {
        return punishments == null ? new PunishmentsConfig() : punishments;
    }

    public UpdatesConfig updates() {
        return updates == null ? new UpdatesConfig() : updates;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public boolean debugLog() {
        return debugLog;
    }
}
