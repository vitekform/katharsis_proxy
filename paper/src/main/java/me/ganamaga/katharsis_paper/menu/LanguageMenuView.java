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
import java.util.Optional;

/**
 * Turns a {@link LanguageMenuModel} into a chest the player can click.
 *
 * <p>Every string here arrives from the proxy already translated, so the
 * companion ships no language files of its own.</p>
 */
public final class LanguageMenuView {

    /** Every language's icon, drawn as its flag by the resource pack. */
    private static final Material ICON = Material.PAPER;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /**
     * Builds the inventory and shows it to {@code player}.
     *
     * @param onboarding whether this is a new player's first-join selector
     */
    public void open(Player player, LanguageMenuModel model, boolean onboarding) {
        LanguageMenuHolder holder = new LanguageMenuHolder(model, onboarding);
        Inventory inventory = Bukkit.createInventory(holder, model.size(), render(model.title()));
        holder.inventory(inventory);

        for (LanguageMenuModel.Entry entry : model.entries()) {
            inventory.setItem(entry.slot(), icon(model, entry));
        }
        player.openInventory(inventory);
    }

    private ItemStack icon(LanguageMenuModel model, LanguageMenuModel.Entry entry) {
        ItemStack item = flagIcon(entry.flagModel());
        ItemMeta meta = item.getItemMeta();

        // Italics are the default for renamed items and read as an accident.
        meta.displayName(render(entry.displayName()).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(render(entry.current() ? model.currentHint() : model.selectHint())
                .decoration(TextDecoration.ITALIC, false)));
        // The glint marks the current language, since every icon is a flag.
        if (entry.current()) {
            meta.setEnchantmentGlintOverride(true);
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Paper carrying the language's flag model, as
     * {@code /give @s paper[custom_model_data={strings:["flags_cz"]}]} makes
     * it.
     *
     * <p>Parsed from that same item syntax because string custom model data
     * only exists from 1.21.4, above the API this jar is built against. Older
     * servers cannot parse it and get plain paper.</p>
     */
    static ItemStack flagIcon(Optional<String> flagModel) {
        return flagModel.map(model -> {
            try {
                return Bukkit.getItemFactory().createItemStack(
                        "minecraft:paper[minecraft:custom_model_data={strings:[\"" + model + "\"]}]");
            } catch (IllegalArgumentException e) {
                return null;
            }
        }).orElseGet(() -> new ItemStack(ICON));
    }

    private Component render(String template) {
        return miniMessage.deserialize(template);
    }
}
