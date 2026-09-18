package me.ganamaga.katharsis_paper.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Turns a {@link SettingsMenuModel} into the {@code /settings} chest, or into
 * the Discord chat question asked on a player's first join.
 *
 * <p>As with the language selector, every string arrives from the proxy
 * already translated.</p>
 */
public final class SettingsMenuView {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** Shows {@code player} their settings. */
    public void open(Player player, SettingsMenuModel model) {
        SettingsMenuHolder holder = new SettingsMenuHolder(SettingsMenuHolder.Kind.SETTINGS, model);
        Inventory inventory = Bukkit.createInventory(holder, SettingsMenuModel.SIZE, render(model.strings().title()));
        holder.inventory(inventory);
        redraw(holder);
        player.openInventory(inventory);
    }

    /** Asks {@code player} whether Discord chat should be shown to them. */
    public void openDiscordChatPrompt(Player player, SettingsMenuModel model) {
        SettingsMenuHolder holder = new SettingsMenuHolder(SettingsMenuHolder.Kind.DISCORD_CHAT_PROMPT, model);
        Inventory inventory = Bukkit.createInventory(holder, SettingsMenuModel.SIZE,
                render(model.strings().promptTitle()));
        holder.inventory(inventory);
        inventory.setItem(SettingsMenuModel.LEFT_SLOT, item(new ItemStack(Material.LIME_WOOL),
                model.strings().promptYes(), null, false));
        inventory.setItem(SettingsMenuModel.RIGHT_SLOT, item(new ItemStack(Material.RED_WOOL),
                model.strings().promptNo(), null, false));
        player.openInventory(inventory);
    }

    /** Draws the settings buttons from the holder's current model. */
    public void redraw(SettingsMenuHolder holder) {
        SettingsMenuModel model = holder.model();
        Inventory inventory = holder.getInventory();
        inventory.setItem(SettingsMenuModel.LEFT_SLOT, item(LanguageMenuView.flagIcon(model.flagModel()),
                model.strings().languageLabel(),
                miniMessage.deserialize(model.strings().languageHint(),
                        Placeholder.parsed("language", model.strings().languageName())),
                false));
        inventory.setItem(SettingsMenuModel.RIGHT_SLOT, item(
                new ItemStack(model.discordChat() ? Material.LIME_DYE : Material.GRAY_DYE),
                model.strings().discordChatLabel(), render(model.discordChatHint()), model.discordChat()));
    }

    private ItemStack item(ItemStack item, String name, Component lore, boolean glint) {
        ItemMeta meta = item.getItemMeta();
        // Italics are the default for renamed items and read as an accident.
        meta.displayName(render(name).decoration(TextDecoration.ITALIC, false));
        if (lore != null) {
            meta.lore(List.of(lore.decoration(TextDecoration.ITALIC, false)));
        }
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Component render(String template) {
        return miniMessage.deserialize(template);
    }
}
