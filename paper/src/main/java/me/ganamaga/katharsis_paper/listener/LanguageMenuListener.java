package me.ganamaga.katharsis_paper.listener;

import me.ganamaga.katharsis_paper.CompanionMessages;
import me.ganamaga.katharsis_paper.menu.LanguageMenuHolder;
import me.ganamaga.katharsis_paper.menu.LanguageMenuModel;
import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Handles clicks in the language selector.
 *
 * <p>The proxy sends the confirmation message once it has stored the choice, so
 * nothing here needs translating.</p>
 *
 * <p>On a new player's first join the selector is followed by the Discord chat
 * question, once the new language is stored so the question is asked in it.
 * Closing the selector without picking still leads on to the question.</p>
 */
public final class LanguageMenuListener implements Listener {

    private final Plugin plugin;
    private final ProxyClient client;
    private final CompanionMessages messages;
    private final Consumer<Player> afterOnboarding;

    /**
     * @param afterOnboarding what follows the first-join selector, whether the
     *                        player picked a language or closed it
     */
    public LanguageMenuListener(Plugin plugin, ProxyClient client, CompanionMessages messages,
                                Consumer<Player> afterOnboarding) {
        this.plugin = plugin;
        this.client = client;
        this.messages = messages;
        this.afterOnboarding = afterOnboarding;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LanguageMenuHolder holder)) {
            return;
        }

        // Ours is a display, not storage: no item may be taken out of it or
        // dropped into it, whatever the player clicks or shift-clicks.
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Optional<LanguageMenuModel.Entry> clicked = holder.model().entryAt(event.getRawSlot());
        if (clicked.isEmpty()) {
            return;
        }

        LanguageMenuModel.Entry entry = clicked.get();
        // Before closing, so the close handler does not move on a second time.
        boolean first = holder.markAnswered();
        player.closeInventory();
        if (entry.current()) {
            // Already their language; closing the menu is answer enough.
            continueOnboarding(holder, player, first);
            return;
        }
        choose(player, entry.code(), () -> continueOnboarding(holder, player, first));
    }

    /** Moves a new player on to the next question when they close the selector without picking. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof LanguageMenuHolder holder)
                || !(event.getPlayer() instanceof Player player)
                || event.getReason() == InventoryCloseEvent.Reason.DISCONNECT) {
            return;
        }
        continueOnboarding(holder, player, holder.markAnswered());
    }

    private void continueOnboarding(LanguageMenuHolder holder, Player player, boolean first) {
        if (holder.onboarding() && first && player.isOnline()) {
            afterOnboarding.accept(player);
        }
    }

    /** Blocks dragging items across the selector, which click alone does not cover. */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof LanguageMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void choose(Player player, String language, Runnable then) {
        client.setLanguage(player, language).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                player.sendMessage(messages.proxyUnreachable());
                plugin.getLogger().log(Level.WARNING,
                        "Could not set the language of " + player.getName() + " to " + language, failure);
            }
            // On success the proxy messages the player itself, in the new
            // language, so there is nothing to say here.
            then.run();
        });
    }
}
