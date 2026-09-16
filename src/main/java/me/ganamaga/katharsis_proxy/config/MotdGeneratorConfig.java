package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The {@code motd.generator} section of {@code config.yml}: turning images into
 * banner MOTDs by uploading their tiles to MineSkin as player skins.
 */
@ConfigSerializable
public class MotdGeneratorConfig {

    private boolean enabled = true;

    /**
     * MineSkin's v1 upload endpoint, which the standalone generator script uses.
     * It is deprecated in favour of v2 but still served.
     */
    private String mineskinUrl = "https://api.mineskin.org/generate/upload";

    /** Optional. MineSkin expects automated clients to send one. */
    private String mineskinApiKey = "";

    /** Anonymous clients are rate limited to roughly one upload per six seconds. */
    private long uploadDelayMs = 6_200L;

    private long retryDelayMs = 10_000L;

    private int maxAttempts = 5;

    public boolean enabled() {
        return enabled;
    }

    public String mineskinUrl() {
        return mineskinUrl;
    }

    public String mineskinApiKey() {
        return mineskinApiKey;
    }

    public long uploadDelayMs() {
        return uploadDelayMs;
    }

    public long retryDelayMs() {
        return retryDelayMs;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
