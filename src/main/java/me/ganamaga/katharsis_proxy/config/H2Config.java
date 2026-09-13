package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Settings for the embedded H2 backend. Documented under {@code database.h2}
 * in {@code config.yml}.
 */
@ConfigSerializable
public class H2Config {

    private String file = "katharsis";

    public String file() {
        return file;
    }
}
