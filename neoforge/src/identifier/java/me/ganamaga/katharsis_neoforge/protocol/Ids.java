package me.ganamaga.katharsis_neoforge.protocol;

import net.minecraft.resources.Identifier;

/** Channel ids. Minecraft 1.21.11 renamed ResourceLocation to Identifier, so this file differs per line. */
final class Ids {

    static final Identifier AUTH = Identifier.fromNamespaceAndPath("katharsis", "auth");
    static final Identifier LANGUAGE = Identifier.fromNamespaceAndPath("katharsis", "language");
    static final Identifier CHAT = Identifier.fromNamespaceAndPath("katharsis", "chat");
    static final Identifier PUNISH = Identifier.fromNamespaceAndPath("katharsis", "punish");

    private Ids() {
    }
}
