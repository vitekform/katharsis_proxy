package me.ganamaga.katharsis_paper.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as the settings menu or the Discord chat question, for
 * the same reasons as {@link LanguageMenuHolder}.
 *
 * <p>Only touched on the server thread.</p>
 */
public final class SettingsMenuHolder implements InventoryHolder {

    /** Which of the two menus this is. */
    public enum Kind {
        /** {@code /settings}. */
        SETTINGS,
        /** The Discord chat question asked on a player's first join. */
        DISCORD_CHAT_PROMPT
    }

    private final Kind kind;
    private SettingsMenuModel model;
    private boolean pending;
    private Inventory inventory;

    public SettingsMenuHolder(Kind kind, SettingsMenuModel model) {
        this.kind = kind;
        this.model = model;
    }

    public Kind kind() {
        return kind;
    }

    public SettingsMenuModel model() {
        return model;
    }

    /** Replaces the model once a change has been stored. */
    public void model(SettingsMenuModel model) {
        this.model = model;
    }

    /** Whether a change is on its way to the proxy, during which clicks are ignored. */
    public boolean pending() {
        return pending;
    }

    public void pending(boolean pending) {
        this.pending = pending;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
