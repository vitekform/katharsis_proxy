package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * HikariCP pool sizing, shared by every backend. Documented under
 * {@code database.pool} in {@code config.yml}.
 */
@ConfigSerializable
public class PoolConfig {

    private int maximumPoolSize = 10;
    private int minimumIdle = 2;
    private long connectionTimeoutMs = 10_000L;
    private long idleTimeoutMs = 600_000L;
    private long maxLifetimeMs = 1_800_000L;

    public int maximumPoolSize() {
        return maximumPoolSize;
    }

    public int minimumIdle() {
        return minimumIdle;
    }

    public long connectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public long idleTimeoutMs() {
        return idleTimeoutMs;
    }

    public long maxLifetimeMs() {
        return maxLifetimeMs;
    }
}
