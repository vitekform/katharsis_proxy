package me.ganamaga.katharsis_proxy.auth;

import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.config.AuthConfig;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import net.kyori.adventure.title.Title;

import java.time.Duration;

/** The login title and chat prompt, the same in the void and on a login server. */
final class AuthPrompt {

    private AuthPrompt() {
    }

    static void show(Messages messages, AuthConfig config, Player player, boolean registered) {
        String language = messages.languageOf(player);
        // Held for the whole login window; it is cleared once they log in.
        Title.Times times = Title.Times.times(Duration.ofMillis(500),
                Duration.ofSeconds(config.loginTimeoutSeconds()), Duration.ofMillis(500));
        player.showTitle(Title.title(
                messages.render(language, MessageKey.AUTH_TITLE),
                messages.render(language, registered
                        ? MessageKey.AUTH_SUBTITLE_LOGIN
                        : MessageKey.AUTH_SUBTITLE_REGISTER),
                times));
        messages.send(player, registered ? MessageKey.AUTH_PROMPT_LOGIN : MessageKey.AUTH_PROMPT_REGISTER);
    }
}
