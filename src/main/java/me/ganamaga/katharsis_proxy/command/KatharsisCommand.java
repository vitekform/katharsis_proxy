package me.ganamaga.katharsis_proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.update.ProxyUpdates;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;
import java.util.Locale;

/**
 * {@code /katharsis}: the running version; {@code /katharsis check} looks for
 * an update, {@code /katharsis update} downloads it to install at the next
 * restart. Needs {@value ProxyUpdates#COMMAND_PERMISSION}.
 */
public final class KatharsisCommand implements SimpleCommand {

    public static final String NAME = "katharsis";

    private static final List<String> SUBCOMMANDS = List.of("check", "update");

    private final Messages messages;
    private final ProxyUpdates updates;

    public KatharsisCommand(Messages messages, ProxyUpdates updates) {
        this.messages = messages;
        this.updates = updates;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(ProxyUpdates.COMMAND_PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1) {
            return List.of();
        }
        return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(typed)).toList();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            messages.send(source, MessageKey.UPDATE_USAGE,
                    Placeholder.unparsed("current", updates.version().toString()));
            return;
        }
        if (!updates.enabled()) {
            messages.send(source, MessageKey.UPDATE_DISABLED);
            return;
        }
        updates.run(source, sub.equals("update"));
    }
}
