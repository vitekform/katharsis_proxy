package me.ganamaga.katharsis_neoforge.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A chest that is a display, not storage: no item can be taken out of it or put
 * into it, whatever the player clicks, shift-clicks or drags, and every click
 * on one of its slots is handed to a {@link Handler} instead.
 *
 * <p>It is a plain chest to the client, so players need no mod to use it.</p>
 */
public final class DisplayMenu extends ChestDisplay {

    /** What a menu does when clicked or closed. Called on the server thread. */
    public interface Handler {

        /** A click on {@code slot} of the chest itself, never of the player's own inventory. */
        void click(DisplayMenu menu, ServerPlayer player, int slot);

        /** The menu was closed, by the player, by another menu opening, or by them leaving. */
        default void closed(DisplayMenu menu, ServerPlayer player) {
        }
    }

    private static final int ROW = 9;

    /** Chest menu types by row count, from one to six. */
    private static final List<MenuType<?>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
            MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

    private final SimpleContainer items;
    private final Handler handler;

    private DisplayMenu(int containerId, Inventory inventory, SimpleContainer items, Handler handler) {
        super(TYPES.get(items.getContainerSize() / ROW - 1), containerId, inventory, items,
                items.getContainerSize() / ROW);
        this.items = items;
        this.handler = handler;
    }

    /**
     * Shows {@code player} a chest of {@code items}, whose size must be one to
     * six whole rows.
     *
     * @return the menu, which can be redrawn through {@link #items()} while open
     */
    public static DisplayMenu open(ServerPlayer player, Component title, SimpleContainer items, Handler handler) {
        DisplayMenu[] opened = new DisplayMenu[1];
        player.openMenu(new SimpleMenuProvider((containerId, inventory, viewer) ->
                opened[0] = new DisplayMenu(containerId, inventory, items, handler), title));
        return opened[0];
    }

    /** The chest's contents; changes reach the client on the next tick. */
    public SimpleContainer items() {
        return items;
    }

    /** Whether {@code player} still has this menu open. */
    public boolean isOpenFor(ServerPlayer player) {
        return player.containerMenu == this;
    }

    @Override
    void click(int slot, Player player) {
        if (player instanceof ServerPlayer viewer && slot >= 0 && slot < items.getContainerSize()) {
            handler.click(this, viewer, slot);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer viewer) {
            handler.closed(this, viewer);
        }
    }
}
