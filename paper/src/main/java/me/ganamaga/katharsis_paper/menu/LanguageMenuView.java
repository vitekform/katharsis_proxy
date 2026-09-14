package me.ganamaga.katharsis_paper.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Turns a {@link LanguageMenuModel} into a chest the player can click.
 *
 * <p>Every string here arrives from the proxy already translated, so the
 * companion ships no language files of its own.</p>
 */
public final class LanguageMenuView {

    /** Icon for a language the player may switch to. */
    private static final Material AVAILABLE_ICON = Material.PAPER;

    /** Icon for the language the player already uses. */
    private static final Material CURRENT_ICON = Material.WRITTEN_BOOK;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** Builds the inventory and shows it to {@code player}. */
    public void open(Player player, LanguageMenuModel model) {
        LanguageMenuHolder holder = new LanguageMenuHolder(model);
        Inventory inventory = Bukkit.createInventory(holder, model.size(), render(model.title()));
        holder.inventory(inventory);

        for (LanguageMenuModel.Entry entry : model.entries()) {
            inventory.setItem(entry.slot(), icon(model, entry));
        }
        player.openInventory(inventory);
    }

    private ItemStack icon(LanguageMenuModel model, LanguageMenuModel.Entry entry) {
        ItemStack item = new ItemStack(entry.current() ? CURRENT_ICON : AVAILABLE_ICON);
        ItemMeta meta = item.getItemMeta();

        // Italics are the default for renamed items and read as an accident.
        meta.displayName(render(entry.displayName()).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(render(entry.current() ? model.currentHint() : model.selectHint())
                .decoration(TextDecoration.ITALIC, false)));
        if (entry.current()) {
            meta.setEnchantmentGlintOverride(true);
        }

        item.setItemMeta(meta);
        return item;
    }

    private Component render(String template) {
        return miniMessage.deserialize(template);
    }
}
