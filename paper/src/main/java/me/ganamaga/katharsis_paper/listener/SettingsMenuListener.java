package me.ganamaga.katharsis_paper.listener;

import me.ganamaga.katharsis_paper.CompanionMessages;
import me.ganamaga.katharsis_paper.command.LanguageCommand;
import me.ganamaga.katharsis_paper.menu.SettingsMenuHolder;
import me.ganamaga.katharsis_paper.menu.SettingsMenuModel;
import me.ganamaga.katharsis_paper.menu.SettingsMenuView;
import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * Handles clicks in the settings menu and the first-join Discord chat
 * question.
 *
 * <p>Toggling Discord chat redraws the menu in place once the proxy has
 * stored the change, and the proxy confirms it in chat, so nothing here needs
 * translating.</p>
 */
public final class SettingsMenuListener implements Listener {

    private final Plugin plugin;
    private final ProxyClient client;
    private final SettingsMenuView view;
    private final LanguageCommand languageCommand;
    private final CompanionMessages messages;

    public SettingsMenuListener(Plugin plugin, ProxyClient client, SettingsMenuView view,
                                LanguageCommand languageCommand, CompanionMessages messages) {
        this.plugin = plugin;
        this.client = client;
        this.view = view;
        this.languageCommand = languageCommand;
        this.messages = messages;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SettingsMenuHolder holder)) {
            return;
        }

        // A display, not storage, exactly like the language selector.
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        switch (holder.kind()) {
            case SETTINGS -> SettingsMenuModel.buttonAt(event.getRawSlot()).ifPresent(button -> {
                switch (button) {
                    case LANGUAGE -> {
                        player.closeInventory();
                        languageCommand.open(player, false);
                    }
                    case DISCORD_CHAT -> toggleDiscordChat(player, holder);
                }
            });
            case DISCORD_CHAT_PROMPT -> SettingsMenuModel.answerAt(event.getRawSlot()).ifPresent(shown -> {
                player.closeInventory();
                client.setDiscordChat(player, shown).whenComplete((response, failure) -> {
                    if (failure != null || response == null || !response.success()) {
                        reportFailure(player, shown, failure);
                    }
                });
            });
        }
    }

    /** Blocks dragging items across either menu, which click alone does not cover. */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SettingsMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void toggleDiscordChat(Player player, SettingsMenuHolder holder) {
        if (holder.pending()) {
            // The last click has not been answered yet.
            return;
        }
        holder.pending(true);
        boolean shown = !holder.model().discordChat();
        client.setDiscordChat(player, shown).whenComplete((response, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    holder.pending(false);
                    if (failure != null || response == null || !response.success()) {
                        reportFailure(player, shown, failure);
                        return;
                    }
                    holder.model(holder.model().withDiscordChat(shown));
                    // Redraw only if the menu is still open; otherwise the
                    // next /settings fetches the new state anyway.
                    if (player.getOpenInventory().getTopInventory().getHolder() == holder) {
                        view.redraw(holder);
                    }
                }));
    }

    private void reportFailure(Player player, boolean shown, Throwable failure) {
        player.sendMessage(messages.proxyUnreachable());
        plugin.getLogger().log(Level.WARNING, "Could not " + (shown ? "show" : "hide")
                + " Discord chat for " + player.getName(), failure);
    }
}
