package me.ganamaga.katharsis_neoforge.protocol;

import net.minecraft.resources.ResourceLocation;

/** Channel ids. Minecraft 1.21.11 renamed ResourceLocation to Identifier, so this file differs per line. */
final class Ids {

    static final ResourceLocation AUTH = ResourceLocation.fromNamespaceAndPath("katharsis", "auth");

    private Ids() {
    }
}
