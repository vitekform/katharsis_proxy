package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code discord} section of {@code config.yml}: the Discord bot that runs
 * alongside the proxy.
 */
@ConfigSerializable
public class DiscordConfig {

    /** Off by default, since there is no token to log in with. */
    private boolean enabled = false;

    private String token = "";

    /** Server id to the id of the Discord channel its chat is posted to. */
    private Map<String, Long> chatChannels = new HashMap<>();

    public boolean enabled() {
        return enabled;
    }

    public String token() {
        return token;
    }

    /** Server ids mapped to Discord channel ids. Servers not listed are not relayed. */
    public Map<String, Long> chatChannels() {
        return chatChannels == null ? Map.of() : chatChannels;
    }
}
