package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Connection settings for the PostgreSQL backend. Documented under
 * {@code database.postgresql} in {@code config.yml}.
 */
@ConfigSerializable
public class PostgresConfig {

    private String host = "localhost";
    private int port = 5432;
    private String database = "katharsis";
    private String username = "katharsis";
    private String password = "";
    private String sslMode = "disable";

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String sslMode() {
        return sslMode;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=" + sslMode;
    }
}
