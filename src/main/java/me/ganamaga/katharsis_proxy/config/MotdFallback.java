package me.ganamaga.katharsis_proxy.config;

/**
 * What to show when a banner MOTD cannot be used, either because the client is
 * too old to render it or because none could be loaded.
 */
public enum MotdFallback {

    /** Show {@link MotdConfig#fallbackMotd()}. This is the default. */
    CONFIG,

    /** Leave the description alone, so the {@code motd} from velocity.toml shows. */
    PROXY
}
