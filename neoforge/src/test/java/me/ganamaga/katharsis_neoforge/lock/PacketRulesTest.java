package me.ganamaga.katharsis_neoforge.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketRulesTest {

    private static final String CUSTOM = "ServerboundCustomPayloadPacket";

    @Test
    @DisplayName("while configuring, everything passes, mods' negotiation included")
    void configuringPassesAll() {
        assertTrue(PacketRules.passes(CUSTOM, "SomeModsPayload", true));
        assertTrue(PacketRules.passes("ServerboundSelectKnownPacks", null, true));
    }

    @Test
    @DisplayName("in the world, only what keeps the connection alive passes")
    void keepAlivePasses() {
        assertTrue(PacketRules.passes("ServerboundKeepAlivePacket", null, false));
        assertTrue(PacketRules.passes("ServerboundAcceptTeleportationPacket", null, false));
        assertTrue(PacketRules.passes("ServerboundClientInformationPacket", null, false));
    }

    @Test
    @DisplayName("anything that acts on the world or the player is dropped")
    void actionsDropped() {
        for (String packet : new String[] {
                "ServerboundMovePlayerPacket$PosRot", "ServerboundUseItemOnPacket", "ServerboundUseItemPacket",
                "ServerboundPlayerActionPacket", "ServerboundContainerClickPacket", "ServerboundInteractPacket",
                "ServerboundSetCarriedItemPacket", "ServerboundClientCommandPacket", "ServerboundChatPacket",
                "ServerboundChatCommandPacket", "ServerboundChatCommandSignedPacket",
                "ServerboundCommandSuggestionPacket", "ServerboundCustomClickActionPacket",
                "SomeModsOwnPacket"}) {
            assertFalse(PacketRules.passes(packet, null, false), packet);
        }
    }

    @Test
    @DisplayName("of custom payloads, only the brand and channel lists pass")
    void payloads() {
        assertTrue(PacketRules.passes(CUSTOM, "BrandPayload", false));
        assertTrue(PacketRules.passes(CUSTOM, "MinecraftRegisterPayload", false));
        assertFalse(PacketRules.passes(CUSTOM, "SomeModsPayload", false));
        assertFalse(PacketRules.passes(CUSTOM, "DiscardedPayload", false));
    }
}
