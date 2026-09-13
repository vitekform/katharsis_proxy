package me.ganamaga.katharsis_proxy.i18n;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Duration;

/**
 * Renders and sends messages in the recipient's language.
 *
 * <p>Templates are MiniMessage, and every value substituted into them is
 * inserted unparsed, so a server or language name can never smuggle formatting
 * tags into a message.</p>
 */
public final class Messages {

    private final LanguageRegistry registry;
    private final PlayerLanguageCache cache;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public Messages(LanguageRegistry registry, PlayerLanguageCache cache) {
        this.registry = registry;
        this.cache = cache;
    }

    public LanguageRegistry registry() {
        return registry;
    }

    /**
     * The language to address {@code source} in: their cached preference, or the
     * configured default for the console and for players whose entry has gone.
     */
    public String languageOf(CommandSource source) {
        if (source instanceof Player player) {
            return cache.get(player.getUniqueId()).orElseGet(registry::defaultLanguage);
        }
        return registry.defaultLanguage();
    }

    /** Renders {@code key} in {@code language}, without the prefix. */
    public Component render(String language, MessageKey key, TagResolver... placeholders) {
        return miniMessage.deserialize(registry.template(language, key), placeholders);
    }

    /** Renders {@code key} in {@code language} behind the prefix. */
    public Component prefixed(String language, MessageKey key, TagResolver... placeholders) {
        return prefix(language).append(render(language, key, placeholders));
    }

    /** The prefix on its own, for wrapping a component that is not a template. */
    public Component prefix(String language) {
        return render(language, MessageKey.PREFIX);
    }

    /** Renders and sends {@code key} to {@code source} in their own language. */
    public void send(CommandSource source, MessageKey key, TagResolver... placeholders) {
        source.sendMessage(prefixed(languageOf(source), key, placeholders));
    }

    /** Sends an already-built component behind the prefix, e.g. a kick reason from a backend. */
    public void sendPrefixed(CommandSource source, Component body) {
        source.sendMessage(prefix(languageOf(source)).append(body));
    }

    /**
     * Formats a cooldown remainder, rounding up so it never reads {@code 0s}.
     * The unit layout itself comes from the language file.
     */
    public Component duration(String language, Duration remaining) {
        long totalSeconds = Math.max(1L, (remaining.toMillis() + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes > 0L) {
            return render(language, MessageKey.DURATION_MINUTES_SECONDS,
                    Placeholder.unparsed("minutes", Long.toString(minutes)),
                    Placeholder.unparsed("seconds", Long.toString(seconds)));
        }
        return render(language, MessageKey.DURATION_SECONDS,
                Placeholder.unparsed("seconds", Long.toString(seconds)));
    }
}
