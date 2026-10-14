package me.ganamaga.katharsis_neoforge.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;

/**
 * A chest whose clicks are read rather than carried out. Minecraft 26.1 renamed
 * ClickType to ContainerInput, so this file differs per line; everything else
 * is in {@link DisplayMenu}.
 */
abstract class ChestDisplay extends ChestMenu {

    ChestDisplay(MenuType<?> type, int containerId, Inventory inventory, Container container, int rows) {
        super(type, containerId, inventory, container, rows);
    }

    /** A click on {@code slot}, or {@code -999} outside the window. Drags never get here. */
    abstract void click(int slot, Player player);

    @Override
    public void clicked(int slot, int button, ContainerInput type, Player player) {
        // Nothing moves: undo whatever the client already drew, then act on it.
        sendAllDataToRemote();
        if (type != ContainerInput.QUICK_CRAFT) {
            click(slot, player);
        }
    }
}
