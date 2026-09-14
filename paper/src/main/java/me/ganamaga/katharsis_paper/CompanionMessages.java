package me.ganamaga.katharsis_paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The handful of strings the companion needs before it has heard from the proxy.
 *
 * <p>Everything a player normally sees is translated on the proxy, next to the
 * language files. These few cannot be: they are what we say when the proxy did
 * not answer, so there is no language to say it in.</p>
 */
public final class CompanionMessages {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final String proxyUnreachable;
    private final String playersOnly;

    public CompanionMessages(FileConfiguration configuration) {
        this.proxyUnreachable = configuration.getString("messages.proxy-unreachable",
                "<red>Could not reach the proxy. Please try again in a moment.");
        this.playersOnly = configuration.getString("messages.players-only",
                "<red>Only players can use this command.");
    }

    public Component proxyUnreachable() {
        return miniMessage.deserialize(proxyUnreachable);
    }

    public Component playersOnly() {
        return miniMessage.deserialize(playersOnly);
    }
}
