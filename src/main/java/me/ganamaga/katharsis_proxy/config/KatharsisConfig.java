package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Root of {@code config.yml}. The shipped default file documents every key.
 */
@ConfigSerializable
public class KatharsisConfig {

    private DatabaseConfig database = new DatabaseConfig();
    private MotdConfig motd = new MotdConfig();
    private String defaultLanguage = "en_US";

    public DatabaseConfig database() {
        return database;
    }

    public MotdConfig motd() {
        return motd;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }
}
