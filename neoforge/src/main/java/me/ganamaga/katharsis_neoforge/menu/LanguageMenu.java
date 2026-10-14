package me.ganamaga.katharsis_neoforge.menu;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.CompanionMessages;
import me.ganamaga.katharsis_neoforge.proxy.ProxyClient;
import me.ganamaga.katharsis_neoforge.text.Texts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The language selector: {@code /language}, and the chest a new player is
 * shown on their first join.
 *
 * <p>Every string arrives from the proxy already translated, and the proxy
 * sends the confirmation itself once it has stored the choice, so the mod
 * ships no language files of its own.</p>
 *
 * <p>On a new player's first join the selector is followed by the Discord chat
 * question, once the new language is stored so the question is asked in it.
 * Closing the selector without picking still leads on to the question.</p>
 */
public final class LanguageMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ProxyClient client;
    private final CompanionMessages messages;
    private final Consumer<ServerPlayer> afterOnboarding;

    /**
     * @param afterOnboarding what follows the first-join selector, whether the
     *                        player picked a language or closed it
     */
    public LanguageMenu(ProxyClient client, CompanionMessages messages, Consumer<ServerPlayer> afterOnboarding) {
        this.client = client;
        this.messages = messages;
        this.afterOnboarding = afterOnboarding;
    }

    /**
     * Fetches the selector from the proxy and opens it for {@code player}, or
     * tells them the proxy is unreachable. Called on the server thread.
     *
     * @param onboarding whether this is a new player's first join, after which
     *                   they are asked about Discord chat
     */
    public void open(ServerPlayer player, boolean onboarding) {
        client.requestMenu(player).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                player.sendSystemMessage(messages.proxyUnreachable());
                LOGGER.warn("Could not fetch the language menu for {}: {}", player.getScoreboardName(),
                        failure != null ? failure.toString() : response);
                return;
            }
            if (!player.hasDisconnected()) {
                show(player, LanguageMenuModel.from(response), onboarding);
            }
        });
    }

    private void show(ServerPlayer player, LanguageMenuModel model, boolean onboarding) {
        SimpleContainer items = new SimpleContainer(model.size());
        for (LanguageMenuModel.Entry entry : model.entries()) {
            items.setItem(entry.slot(), icon(model, entry));
        }
        DisplayMenu.open(player, Texts.component(model.title()), items, new Selector(model, onboarding));
    }

    private static ItemStack icon(LanguageMenuModel model, LanguageMenuModel.Entry entry) {
        // The glint marks the current language, since every icon is a flag.
        return Icons.describe(Icons.flag(entry.flagModel()), entry.displayName(),
                Texts.itemText(entry.current() ? model.currentHint() : model.selectHint()), entry.current());
    }

    /** One open selector. */
    private final class Selector implements DisplayMenu.Handler {

        private final LanguageMenuModel model;
        private final boolean onboarding;
        private boolean answered;

        Selector(LanguageMenuModel model, boolean onboarding) {
            this.model = model;
            this.onboarding = onboarding;
        }

        @Override
        public void click(DisplayMenu menu, ServerPlayer player, int slot) {
            Optional<LanguageMenuModel.Entry> clicked = model.entryAt(slot);
            if (clicked.isEmpty()) {
                return;
            }
            LanguageMenuModel.Entry entry = clicked.get();
            // Before closing, so closed() does not move on a second time.
            boolean first = markAnswered();
            player.closeContainer();
            if (entry.current()) {
                // Already their language; closing the menu is answer enough.
                continueOnboarding(player, first);
                return;
            }
            client.setLanguage(player, entry.code()).whenComplete((response, failure) -> {
                if (failure != null || response == null || !response.success()) {
                    player.sendSystemMessage(messages.proxyUnreachable());
                    LOGGER.warn("Could not set the language of {} to {}: {}", player.getScoreboardName(),
                            entry.code(), failure != null ? failure.toString() : response);
                }
                // On success the proxy messages the player itself, in the new
                // language, so there is nothing to say here.
                continueOnboarding(player, first);
            });
        }

        /** Moves a new player on to the next question when they close the selector without picking. */
        @Override
        public void closed(DisplayMenu menu, ServerPlayer player) {
            if (!player.hasDisconnected()) {
                continueOnboarding(player, markAnswered());
            }
        }

        private boolean markAnswered() {
            if (answered) {
                return false;
            }
            answered = true;
            return true;
        }

        private void continueOnboarding(ServerPlayer player, boolean first) {
            if (onboarding && first && !player.hasDisconnected()) {
                afterOnboarding.accept(player);
            }
        }
    }
}
