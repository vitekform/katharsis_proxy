package me.ganamaga.katharsis_proxy.moderation;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;

import java.util.Optional;
import java.util.UUID;

/**
 * Warns the player in their own language, straight from the proxy, so it works
 * on backends without the companion plugin.
 *
 * <p>The warning never repeats what was written, and never names the rule that
 * caught it: that would only tell the author which spelling to try next. It
 * does distinguish a message that broke the rules from one that could not be
 * checked at all, because the second is worth trying again and the first is
 * not.</p>
 */
public final class PlayerModerationNotice implements ModerationNotice {

    private final ProxyServer proxy;
    private final Messages messages;

    public PlayerModerationNotice(ProxyServer proxy, Messages messages) {
        this.proxy = proxy;
        this.messages = messages;
    }

    @Override
    public void held(UUID playerUuid, ModerationVerdict verdict) {
        Optional<Player> player = proxy.getPlayer(playerUuid);
        if (player.isEmpty()) {
            // They spoke and left. Nothing to say to.
            return;
        }
        MessageKey key = verdict.source() != null && verdict.source().isViolation()
                ? MessageKey.CHAT_BLOCKED
                : MessageKey.CHAT_UNCHECKED;
        messages.send(player.get(), key);
    }
}
