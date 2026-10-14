package me.ganamaga.katharsis_neoforge.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import me.ganamaga.katharsis_neoforge.text.Texts;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Optional;

/** The items the menus are drawn with. */
final class Icons {

    private Icons() {
    }

    /**
     * Paper carrying a language's flag model, as
     * {@code /give @s paper[custom_model_data={strings:["flags_cz"]}]} makes it.
     *
     * <p>String custom model data only exists from 1.21.4, so it is read
     * through the component's own codec rather than built: before 1.21.4 that
     * codec wants a number, refuses the strings, and the item stays plain
     * paper, as it does on Paper.</p>
     */
    static ItemStack flag(Optional<String> flagModel) {
        ItemStack item = new ItemStack(Items.PAPER);
        flagModel.ifPresent(model -> {
            JsonArray strings = new JsonArray();
            strings.add(model);
            JsonObject data = new JsonObject();
            data.add("strings", strings);
            setParsed(item, DataComponents.CUSTOM_MODEL_DATA, data);
        });
        return item;
    }

    private static <T> void setParsed(ItemStack item, DataComponentType<T> type, JsonObject json) {
        type.codecOrThrow().parse(JsonOps.INSTANCE, json).result().ifPresent(value -> item.set(type, value));
    }

    /**
     * Names {@code item} and gives it one line of lore.
     *
     * @param name  a MiniMessage template from the proxy
     * @param lore  already rendered, or null for none
     * @param glint whether it shines, which marks the current choice
     */
    static ItemStack describe(ItemStack item, String name, Component lore, boolean glint) {
        // Upright: italics are the default for renamed items and read as an accident.
        item.set(DataComponents.CUSTOM_NAME, Texts.itemText(name));
        if (lore != null) {
            item.set(DataComponents.LORE, new ItemLore(List.of(lore)));
        }
        if (glint) {
            item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return item;
    }
}
