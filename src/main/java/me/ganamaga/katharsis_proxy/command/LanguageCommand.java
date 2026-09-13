package me.ganamaga.katharsis_proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shows and changes the caller's language.
 *
 * <p>Without it nothing could ever move a player off the configured default, so
 * the rest of the translation machinery would be unreachable.</p>
 */
public final class LanguageCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final Messages messages;
    private final PlayerRepository players;
    private final PlayerLanguageCache cache;

    public LanguageCommand(ProxyServer proxy, Object plugin, Logger logger, Messages messages,
                           PlayerRepository players, PlayerLanguageCache cache) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.messages = messages;
        this.players = players;
        this.cache = cache;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return messages.registry().languages().stream()
                .filter(language -> language.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            messages.send(invocation.source(), MessageKey.PLAYERS_ONLY);
            return;
        }

        LanguageRegistry registry = messages.registry();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            String current = messages.languageOf(player);
            player.sendMessage(messages.prefixed(current, MessageKey.LANGUAGE_CURRENT,
                    Placeholder.unparsed("language", current)));
            player.sendMessage(messages.prefixed(current, MessageKey.LANGUAGE_AVAILABLE,
                    Placeholder.unparsed("languages", String.join(", ", registry.languages()))));
            return;
        }

        String requested = LanguageRegistry.normalise(args[0]);
        if (!registry.isKnown(requested)) {
            player.sendMessage(messages.prefixed(messages.languageOf(player), MessageKey.LANGUAGE_UNKNOWN,
                    Placeholder.unparsed("language", args[0])));
            player.sendMessage(messages.prefixed(messages.languageOf(player), MessageKey.LANGUAGE_AVAILABLE,
                    Placeholder.unparsed("languages", String.join(", ", registry.languages()))));
            return;
        }

        // Off the command thread: writing the preference must not block chat.
        proxy.getScheduler().buildTask(plugin, () -> save(player, requested)).schedule();
    }

    private void save(Player player, String language) {
        try {
            UUID playerId = player.getUniqueId();
            if (!players.updateLanguage(playerId, language)) {
                // No row yet, e.g. the database was unreachable when they joined.
                players.createIfAbsent(playerId, language);
            }
            cache.put(playerId, language);
            player.sendMessage(messages.prefixed(language, MessageKey.LANGUAGE_CHANGED,
                    Placeholder.unparsed("language", language)));
        } catch (SQLException | IllegalArgumentException e) {
            messages.send(player, MessageKey.LANGUAGE_FAILED);
            logger.error("Could not save the language of {} ({}).",
                    player.getUsername(), player.getUniqueId(), e);
        }
    }
}
