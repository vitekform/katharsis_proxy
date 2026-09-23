package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The {@code auth} section of {@code config.yml}: the password every player
 * without a Mojang account logs in with.
 */
@ConfigSerializable
public class AuthConfig {

    private static final String DEFAULT_MOJANG_URL = "https://api.mojang.com/users/profiles/minecraft/";

    /**
     * On by default: on an offline-mode proxy anyone can join under any name,
     * so without this every account is open to whoever types it first.
     */
    private boolean enabled = true;

    /** Kicked if not logged in within this long. */
    private int loginTimeoutSeconds = 60;

    /** Kicked after this many wrong passwords in one connection. */
    private int maxAttempts = 3;

    private int minPasswordLength = 8;

    /**
     * Longer passwords are refused rather than truncated. Argon2 takes any
     * length, this only stops a client from making every attempt expensive.
     */
    private int maxPasswordLength = 64;

    /**
     * Names Mojang knows are sent through Mojang's own login, and those players
     * never see the login screen.
     */
    private boolean premiumSkip = true;

    /** Where a name is looked up to tell whether a Mojang account holds it. */
    private String mojangUrl = DEFAULT_MOJANG_URL;

    private long mojangTimeoutMs = 5_000L;

    private Argon2Config argon2 = new Argon2Config();

    public boolean enabled() {
        return enabled;
    }

    public int loginTimeoutSeconds() {
        return Math.max(10, loginTimeoutSeconds);
    }

    public int maxAttempts() {
        return Math.max(1, maxAttempts);
    }

    public int minPasswordLength() {
        return Math.max(1, minPasswordLength);
    }

    public int maxPasswordLength() {
        return Math.max(minPasswordLength(), maxPasswordLength);
    }

    public boolean premiumSkip() {
        return premiumSkip;
    }

    public String mojangUrl() {
        return mojangUrl == null || mojangUrl.isBlank() ? DEFAULT_MOJANG_URL : mojangUrl.strip();
    }

    public long mojangTimeoutMs() {
        return Math.max(500L, mojangTimeoutMs);
    }

    public Argon2Config argon2() {
        return argon2 == null ? new Argon2Config() : argon2;
    }

    /**
     * Cost of each new hash. Changing these does not lock anyone out: every
     * stored hash carries its own parameters, and is redone with these the
     * next time its owner logs in.
     */
    @ConfigSerializable
    public static class Argon2Config {

        /** OWASP's recommended minimum for Argon2id: 19 MiB, 2 passes, 1 lane. */
        private int memoryKib = 19_456;

        private int iterations = 2;

        private int parallelism = 1;

        public Argon2Config() {
        }

        public Argon2Config(int memoryKib, int iterations, int parallelism) {
            this.memoryKib = memoryKib;
            this.iterations = iterations;
            this.parallelism = parallelism;
        }

        public int memoryKib() {
            return Math.max(8 * parallelism(), memoryKib);
        }

        public int iterations() {
            return Math.max(1, iterations);
        }

        public int parallelism() {
            return Math.max(1, parallelism);
        }
    }
}
