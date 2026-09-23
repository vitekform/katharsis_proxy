package me.ganamaga.katharsis_proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;

import java.util.Collections;
import java.util.List;

/**
 * {@code /login} and {@code /register} once logged in, where they only say so.
 *
 * <p>The real ones live in the login void. These exist so a password typed out
 * of habit is answered by the proxy rather than forwarded to a backend, whose
 * console would log the command, password and all.</p>
 */
public final class AuthCommand implements SimpleCommand {

    public static final List<String> NAMES = List.of("login", "register");

    private final Messages messages;

    public AuthCommand(Messages messages) {
        this.messages = messages;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }

    @Override
    public void execute(Invocation invocation) {
        messages.send(invocation.source(), MessageKey.AUTH_ALREADY_LOGGED_IN);
    }
}
