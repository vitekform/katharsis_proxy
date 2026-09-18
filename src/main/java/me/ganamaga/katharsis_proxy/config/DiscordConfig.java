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

    /** The Discord server /link is registered in and the linked role is granted in. */
    private long guildId = 0L;

    /** Role granted to members who link their Minecraft account. 0 grants none. */
    private long linkedRoleId = 0L;

    /** Server id to the id of the Discord channel its chat is posted to. */
    private Map<String, Long> chatChannels = new HashMap<>();

    /**
     * Deliver what linked members write in the chat channels to players on the
     * servers mapped to them. Needs the privileged Message Content intent.
     */
    private boolean relayToMinecraft = true;

    public boolean enabled() {
        return enabled;
    }

    public String token() {
        return token;
    }

    public long guildId() {
        return guildId;
    }

    public long linkedRoleId() {
        return linkedRoleId;
    }

    /** Server ids mapped to Discord channel ids. Servers not listed are not relayed. */
    public Map<String, Long> chatChannels() {
        return chatChannels == null ? Map.of() : chatChannels;
    }

    /** Whether messages in the chat channels are delivered in game. */
    public boolean relayToMinecraft() {
        return relayToMinecraft;
    }
}
