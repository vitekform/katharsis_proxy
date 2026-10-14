package me.ganamaga.katharsis_neoforge;

import me.ganamaga.katharsis_neoforge.config.CompanionSettings;
import me.ganamaga.katharsis_neoforge.text.Texts;
import net.minecraft.network.chat.Component;

/**
 * The handful of strings the mod needs before it has heard from the proxy.
 *
 * <p>Everything a player normally sees is translated on the proxy, next to the
 * language files. These few cannot be: they are what we say when the proxy did
 * not answer, so there is no language to say it in.</p>
 */
public final class CompanionMessages {

    private final String proxyUnreachable;
    private final String playersOnly;

    public CompanionMessages(CompanionSettings settings) {
        this.proxyUnreachable = settings.proxyUnreachable();
        this.playersOnly = settings.playersOnly();
    }

    public Component proxyUnreachable() {
        return Texts.component(proxyUnreachable);
    }

    public Component playersOnly() {
        return Texts.component(playersOnly);
    }
}
