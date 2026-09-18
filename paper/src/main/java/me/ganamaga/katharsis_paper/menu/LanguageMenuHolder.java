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
    private final boolean onboarding;
    private boolean answered;
    private Inventory inventory;

    /**
     * @param onboarding whether this is the selector a new player is shown on
     *                   their first join, which the Discord chat question follows
     */
    public LanguageMenuHolder(LanguageMenuModel model, boolean onboarding) {
        this.model = model;
        this.onboarding = onboarding;
    }

    public LanguageMenuModel model() {
        return model;
    }

    /** Whether this selector is part of a new player's first join. */
    public boolean onboarding() {
        return onboarding;
    }

    /**
     * Marks the selector as dealt with, so the next step of a first join is
     * started once only.
     *
     * @return {@code true} the first time, {@code false} after that
     */
    public boolean markAnswered() {
        if (answered) {
            return false;
        }
        answered = true;
        return true;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
