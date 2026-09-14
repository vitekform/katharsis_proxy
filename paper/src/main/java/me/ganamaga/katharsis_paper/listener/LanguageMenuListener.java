package me.ganamaga.katharsis_paper.listener;

import me.ganamaga.katharsis_paper.CompanionMessages;
import me.ganamaga.katharsis_paper.menu.LanguageMenuHolder;
import me.ganamaga.katharsis_paper.menu.LanguageMenuModel;
import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Handles clicks in the language selector.
 *
 * <p>The proxy sends the confirmation message once it has stored the choice, so
 * nothing here needs translating.</p>
 */
public final class LanguageMenuListener implements Listener {

    private final Plugin plugin;
    private final ProxyClient client;
    private final CompanionMessages messages;

    public LanguageMenuListener(Plugin plugin, ProxyClient client, CompanionMessages messages) {
        this.plugin = plugin;
        this.client = client;
        this.messages = messages;
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
        player.closeInventory();
        if (entry.current()) {
            // Already their language; closing the menu is answer enough.
            return;
        }
        choose(player, entry.code());
    }

    /** Blocks dragging items across the selector, which click alone does not cover. */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof LanguageMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void choose(Player player, String language) {
        client.setLanguage(player, language).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                player.sendMessage(messages.proxyUnreachable());
                plugin.getLogger().log(Level.WARNING,
                        "Could not set the language of " + player.getName() + " to " + language, failure);
            }
            // On success the proxy messages the player itself, in the new
            // language, so there is nothing to say here.
        });
    }
}
