package me.ganamaga.katharsis_neoforge.protocol;

import net.minecraft.resources.ResourceLocation;

/** Channel ids. Minecraft 1.21.11 renamed ResourceLocation to Identifier, so this file differs per line. */
final class Ids {

    static final ResourceLocation AUTH = ResourceLocation.fromNamespaceAndPath("katharsis", "auth");
    static final ResourceLocation LANGUAGE = ResourceLocation.fromNamespaceAndPath("katharsis", "language");
    static final ResourceLocation CHAT = ResourceLocation.fromNamespaceAndPath("katharsis", "chat");
    static final ResourceLocation PUNISH = ResourceLocation.fromNamespaceAndPath("katharsis", "punish");

    private Ids() {
    }
}
