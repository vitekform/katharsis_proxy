package me.ganamaga.katharsis_proxy.config;

/**
 * Storage backend for player data.
 */
public enum DatabaseType {

    /** Embedded file database. Needs no setup, so it is the default. */
    H2,

    /** External PostgreSQL server, configured under {@code database.postgresql}. */
    POSTGRESQL
}
