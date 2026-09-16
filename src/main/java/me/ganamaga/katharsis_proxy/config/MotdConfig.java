package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The {@code motd} section of {@code config.yml}: the server list ping the
 * proxy answers with.
 *
 * <p>A "banner" MOTD is an ordinary chat component whose glyphs only line up
 * into a picture on clients inside {@link #minProtocol()}..{@link #maxProtocol()}.
 * Anything outside that range is served {@link #fallback()} instead, so older
 * clients see readable text rather than a row of broken characters.</p>
 */
@ConfigSerializable
public class MotdConfig {

    /**
     * Off by default: with no {@code motd.json} in the data directory there is
     * nothing to serve, and taking over the ping would only replace the
     * operator's velocity.toml MOTD with a fallback string.
     */
    private boolean enabled = false;

    private MotdMode mode = MotdMode.RANDOM;

    /** How many {@code motd*.json} files to look for. */
    private int count = 1;

    private long rollingIntervalSeconds = 60L;

    /** Lowest protocol version that renders a banner correctly (1.21.9). */
    private int minProtocol = 773;

    /** Highest protocol version that renders a banner correctly (1.21.11). */
    private int maxProtocol = 774;

    private MotdFallback fallback = MotdFallback.CONFIG;

    private String fallbackMotd =
            "&4This server's MOTD only renders on Minecraft 1.21.9-1.21.11.";

    private boolean overrideVersionMessage = false;
    private String customVersionMessage = "&cMaintenance";

    private boolean alwaysOutOfDate = false;

    /**
     * Protocol advertised while {@link #alwaysOutOfDate()} is on. Any value the
     * client does not share makes it draw the version line as out of date.
     */
    private int outOfDateProtocol = 773;

    private boolean hidePlayerCount = false;
    private boolean fakePlayerCount = false;
    private int fakePlayerPercentage = 10;
    private int maxPlayers = 67;

    private MotdGeneratorConfig generator = new MotdGeneratorConfig();

    public boolean enabled() {
        return enabled;
    }

    public MotdMode mode() {
        return mode;
    }

    public int count() {
        return count;
    }

    public long rollingIntervalSeconds() {
        return rollingIntervalSeconds;
    }

    public int minProtocol() {
        return minProtocol;
    }

    public int maxProtocol() {
        return maxProtocol;
    }

    public MotdFallback fallback() {
        return fallback;
    }

    public String fallbackMotd() {
        return fallbackMotd;
    }

    public boolean overrideVersionMessage() {
        return overrideVersionMessage;
    }

    public String customVersionMessage() {
        return customVersionMessage;
    }

    public boolean alwaysOutOfDate() {
        return alwaysOutOfDate;
    }

    public int outOfDateProtocol() {
        return outOfDateProtocol;
    }

    public boolean hidePlayerCount() {
        return hidePlayerCount;
    }

    public boolean fakePlayerCount() {
        return fakePlayerCount;
    }

    public int fakePlayerPercentage() {
        return fakePlayerPercentage;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public MotdGeneratorConfig generator() {
        return generator;
    }

    /** Whether a client on {@code protocol} can render a banner MOTD. */
    public boolean supportsBanner(int protocol) {
        return protocol >= minProtocol && protocol <= maxProtocol;
    }

    /** Online count to advertise while {@link #fakePlayerCount()} is on. */
    public int fakeOnlinePlayers() {
        return Math.clamp((long) maxPlayers * fakePlayerPercentage / 100, 0, Math.max(0, maxPlayers));
    }

    /** Whether either version override is on, i.e. whether to rewrite it at all. */
    public boolean rewritesVersion() {
        return overrideVersionMessage || alwaysOutOfDate;
    }
}
