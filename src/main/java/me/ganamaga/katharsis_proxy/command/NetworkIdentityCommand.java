package me.ganamaga.katharsis_proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.identity.NetworkIdentity;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /kidentity}: the fingerprint of the network's GNLib identity, which
 * every NeoForge backend running GNLib is configured with.
 * {@code /kidentity rotate confirm} changes the key, announced by the old one.
 * Needs {@value #PERMISSION}.
 */
public final class NetworkIdentityCommand implements SimpleCommand {

    public static final String NAME = "kidentity";

    public static final String PERMISSION = "katharsis.identity";

    private final Messages messages;
    private final Supplier<NetworkIdentity> identity;
    private final Consumer<NetworkIdentity> rotated;
    private final Clock clock;

    /**
     * @param rotated takes the new identity after a rotation, so the proxy
     *                certifies with it from then on
     */
    public NetworkIdentityCommand(Messages messages, Supplier<NetworkIdentity> identity,
                                  Consumer<NetworkIdentity> rotated, Clock clock) {
        this.messages = messages;
        this.identity = identity;
        this.rotated = rotated;
        this.clock = clock;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return "rotate".startsWith(typed) ? List.of("rotate") : List.of();
        }
        return List.of();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        NetworkIdentity current = identity.get();
        if (current == null) {
            messages.send(source, MessageKey.IDENTITY_NONE);
            return;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("rotate")) {
            messages.send(source, MessageKey.IDENTITY_FINGERPRINT,
                    Placeholder.unparsed("fingerprint", current.fingerprint()));
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            messages.send(source, MessageKey.IDENTITY_ROTATE_CONFIRM,
                    Placeholder.unparsed("fingerprint", current.fingerprint()));
            return;
        }
        try {
            NetworkIdentity next = current.rotate(clock.millis() / 1000L);
            rotated.accept(next);
            messages.send(source, MessageKey.IDENTITY_ROTATED,
                    Placeholder.unparsed("old", current.fingerprint()),
                    Placeholder.unparsed("new", next.fingerprint()));
        } catch (IOException | RuntimeException e) {
            messages.send(source, MessageKey.IDENTITY_ROTATE_FAILED, Placeholder.unparsed("error", e.toString()));
        }
    }
}
