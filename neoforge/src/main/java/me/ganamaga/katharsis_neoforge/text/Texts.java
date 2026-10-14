package me.ganamaga.katharsis_neoforge.text;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/** Turns the proxy's MiniMessage templates into components, through {@link MiniText}. */
public final class Texts {

    private Texts() {
    }

    /** {@code template} as chat or a menu title would show it. */
    public static Component component(String template) {
        return component(template, false);
    }

    /** {@code template} as an item's name or lore line, without the italics Minecraft gives those. */
    public static Component itemText(String template) {
        return component(template, true);
    }

    private static Component component(String template, boolean upright) {
        JsonObject json = MiniText.toJson(template, upright);
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json).result()
                .orElseGet(() -> Component.literal(MiniText.plain(template)));
    }
}
