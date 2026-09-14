package me.ganamaga.katharsis_proxy.config;

/**
 * How the proxy picks between the banner MOTDs it has loaded. Only meaningful
 * when more than one is present.
 */
public enum MotdMode {

    /** Pick one at random for every ping. This is the default. */
    RANDOM,

    /**
     * Serve one at a time, advancing every
     * {@link MotdConfig#rollingIntervalSeconds()} seconds so everyone pinging
     * in the same window sees the same banner.
     */
    ROLLING
}
