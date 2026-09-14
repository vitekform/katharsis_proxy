package me.ganamaga.katharsis_paper.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as ours.
 *
 * <p>Identifying the selector by its holder rather than by its title means a
 * player cannot fool the click handler by renaming a chest, and it survives the
 * title being translated.</p>
 */
public final class LanguageMenuHolder implements InventoryHolder {

    private final LanguageMenuModel model;
    private Inventory inventory;

    public LanguageMenuHolder(LanguageMenuModel model) {
        this.model = model;
    }

    public LanguageMenuModel model() {
        return model;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
