package me.ganamaga.katharsis_neoforge;

import me.ganamaga.katharsis_neoforge.lock.Locks;
import me.ganamaga.katharsis_neoforge.protocol.AuthPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Backend companion for the Katharsis Velocity proxy, on NeoForge servers.
 *
 * <p>Every player who joins is held in place, unable to do anything, until the
 * proxy says they have logged in. That lets players whose modded client cannot
 * enter the proxy's login void log in on this server instead. Server-side only:
 * players do not need it installed.</p>
 */
@Mod(value = KatharsisNeoForge.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class KatharsisNeoForge {

    public static final String MOD_ID = "katharsis";

    public KatharsisNeoForge(IEventBus modBus) {
        Locks locks = new Locks();
        modBus.addListener(RegisterPayloadHandlersEvent.class, AuthPayload::register);
        modBus.addListener(RegisterConfigurationTasksEvent.class, locks::onConfiguration);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, locks::onLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, locks::onLoggedOut);
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, locks::onDamage);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, locks::onTick);
    }
}
