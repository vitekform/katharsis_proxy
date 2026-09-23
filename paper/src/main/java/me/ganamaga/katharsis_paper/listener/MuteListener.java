package me.ganamaga.katharsis_paper.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.ganamaga.katharsis_paper.mute.MuteRegistry;
import me.ganamaga.katharsis_paper.mute.MuteState;
import me.ganamaga.katharsis_paper.protocol.PunishChannel;
import me.ganamaga.katharsis_paper.protocol.PunishCodec;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Optional;

/**
 * Enforces mutes the proxy tells this server about.
 *
 * <p>Chat and the blocked commands are cancelled as early as possible, so no
 * other plugin sees them and {@link ChatListener} never reports them. The
 * reason is not shown here: the proxy tells the player, in their language,
 * when told they tried to speak.</p>
 */
public final class MuteListener implements Listener, PluginMessageListener {

    private final Plugin plugin;
    private final MuteRegistry mutes;

    public MuteListener(Plugin plugin, MuteRegistry mutes) {
        this.plugin = plugin;
        this.mutes = mutes;
    }

    /** Asks the proxy about everyone online, for when this plugin starts or reloads with players on. */
    public void resync() {
        plugin.getServer().getOnlinePlayers().forEach(this::query);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (muted(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
            attempted(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Optional<MuteState> mute = muted(event.getPlayer());
        if (mute.isPresent() && mute.get().blocks(event.getMessage())) {
            event.setCancelled(true);
            attempted(event.getPlayer());
        }
    }

    /**
     * The proxy pushes the state as the player arrives; asking again covers
     * the push arriving before this server was ready for it.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        query(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mutes.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!PunishChannel.CHANNEL.equals(channel)) {
            return;
        }
        PunishCodec.Update update = PunishCodec.decodeState(message);
        if (update == null) {
            plugin.getLogger().fine("Ignored a malformed message on " + PunishChannel.CHANNEL + ".");
            return;
        }
        mutes.update(update.uuid(), update.state());
    }

    private Optional<MuteState> muted(Player player) {
        return mutes.activeMute(player.getUniqueId(), System.currentTimeMillis());
    }

    private void query(Player player) {
        player.sendPluginMessage(plugin, PunishChannel.CHANNEL, PunishCodec.encodeQuery(player.getUniqueId()));
    }

    private void attempted(Player player) {
        player.sendPluginMessage(plugin, PunishChannel.CHANNEL, PunishCodec.encodeAttempt(player.getUniqueId()));
    }
}
