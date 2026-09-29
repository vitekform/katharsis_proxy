package me.ganamaga.katharsis_neoforge.lock;

import java.util.Set;

/**
 * Which packets a player who has not logged in may still send.
 *
 * <p>Everything else is dropped before the server reads it. This is an
 * allowlist on purpose: mods add their own packets, and a list of what to block
 * would always be one mod behind. Classes are matched by simple name, which
 * Mojang's mappings keep the same across releases, so a packet that only exists
 * in newer versions can be named here without breaking older ones.</p>
 */
public final class PacketRules {

    /**
     * Keep the connection alive and in step, and nothing more. None of these
     * changes the world or the player.
     */
    static final Set<String> PACKETS = Set.of(
            "ServerboundKeepAlivePacket",
            "ServerboundPongPacket",
            "ServerboundPingRequestPacket",
            "ServerboundClientInformationPacket",
            "ServerboundResourcePackPacket",
            "ServerboundCookieResponsePacket",
            "ServerboundConfigurationAcknowledgedPacket",
            // Answers the teleports that hold the player in place.
            "ServerboundAcceptTeleportationPacket",
            "ServerboundChunkBatchReceivedPacket",
            "ServerboundClientTickEndPacket",
            "ServerboundPlayerLoadedPacket",
            // Chat bookkeeping; the messages themselves are dropped.
            "ServerboundChatAckPacket",
            "ServerboundChatSessionUpdatePacket");

    /** Payloads inside a custom payload packet: the client's name and channel lists. */
    static final Set<String> PAYLOADS = Set.of(
            "BrandPayload",
            "MinecraftRegisterPayload",
            "MinecraftUnregisterPayload");

    private PacketRules() {
    }

    /**
     * @param packet      the packet's simple class name
     * @param payload     for a custom payload packet, the payload's simple class name; otherwise null
     * @param configuring whether the connection is still being configured. Nothing is
     *                    dropped then: the player is not in the world yet, and NeoForge
     *                    negotiates the mods' channels over custom payloads.
     */
    public static boolean passes(String packet, String payload, boolean configuring) {
        if (configuring) {
            return true;
        }
        if (payload != null) {
            return PAYLOADS.contains(payload);
        }
        return PACKETS.contains(packet);
    }
}
