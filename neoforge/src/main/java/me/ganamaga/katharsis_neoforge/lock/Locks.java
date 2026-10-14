package me.ganamaga.katharsis_neoforge.lock;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelPipeline;
import me.ganamaga.katharsis_neoforge.protocol.AuthCodec;
import me.ganamaga.katharsis_neoforge.protocol.AuthPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts a {@link Lock} on every connection, and keeps the players behind one
 * safe and still until it opens.
 *
 * <p>Held players cannot act, since the lock drops what they send. What is left
 * is what the world does to them: they take no damage, and every second they
 * are put back where they joined, so their screen matches where the server has
 * them. Nothing here is saved with the player, so a crash while one is held
 * leaves their data as it was.</p>
 */
public final class Locks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often a held player is put back in place, in ticks. */
    private static final int HOLD_INTERVAL = 20;

    private static final String REFUSED = "The server could not hold you until you log in. Please try again.";

    /** Players who have joined and are held, or were just let go and need putting in place once more. */
    private final Map<UUID, Lock> held = new ConcurrentHashMap<>();

    private int ticks;

    /** While the connection is still being configured: before the player can send anything that counts. */
    public void onConfiguration(RegisterConfigurationTasksEvent event) {
        Connection connection = event.getListener().getConnection();
        ChannelPipeline pipeline = connection.channel().pipeline();
        if (pipeline.get(Lock.NAME) != null) {
            return;
        }
        try {
            pipeline.addBefore(Lock.PACKET_HANDLER, Lock.NAME, new Lock(connection));
        } catch (NoSuchElementException | IllegalArgumentException e) {
            LOGGER.error("Could not put the login lock on a connection; refusing it.", e);
            event.getListener().disconnect(Component.literal(REFUSED));
        }
    }

    /** Tells the proxy the player is held, which it answers with an unlock once they have logged in. */
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Connection connection = player.connection.getConnection();
        if (!(connection.channel().pipeline().get(Lock.NAME) instanceof Lock lock)) {
            LOGGER.error("{} joined without the login lock; disconnecting.", player.getScoreboardName());
            player.connection.disconnect(Component.literal(REFUSED));
            return;
        }
        lock.attach(player);
        if (!lock.isLocked()) {
            return;
        }
        held.put(player.getUUID(), lock);
        // Straight onto the connection: NeoForge refuses to send on a channel the
        // client did not negotiate, and the client never has this one. The proxy
        // takes the message before it reaches the client.
        connection.send(new ClientboundCustomPayloadPacket(new AuthPayload(AuthCodec.encodeLocked(player.getUUID()))));
    }

    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Lock lock = held.get(event.getEntity().getUUID());
        if (lock != null && lock.player() == event.getEntity()) {
            held.remove(event.getEntity().getUUID(), lock);
        }
    }

    public void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isHeld(player)) {
            event.setCanceled(true);
        }
    }

    public void onTick(ServerTickEvent.Post event) {
        boolean hold = ++ticks % HOLD_INTERVAL == 0;
        held.values().removeIf(lock -> {
            ServerPlayer player = lock.player();
            if (lock.isLocked()) {
                if (hold) {
                    putBack(player);
                }
                return false;
            }
            // The client moved on its own while held; show it where it really is.
            putBack(player);
            return true;
        });
    }

    /** Whether {@code player} is still waiting to log in on the proxy. */
    public boolean isHeld(ServerPlayer player) {
        Lock lock = held.get(player.getUUID());
        return lock != null && lock.player() == player && lock.isLocked();
    }

    private static void putBack(ServerPlayer player) {
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }
}
