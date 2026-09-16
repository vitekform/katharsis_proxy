package me.ganamaga.katharsis_paper.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.ganamaga.katharsis_paper.protocol.ChatChannel;
import me.ganamaga.katharsis_paper.protocol.ChatCodec;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Reports every chat message to the proxy, which logs it to the database.
 *
 * <p>Listens at {@link EventPriority#MONITOR} so it sees the message as it was
 * finally sent, after other plugins have had their say, and skips messages
 * that were cancelled and so never appeared in chat.</p>
 */
public final class ChatListener implements Listener {

    private final Plugin plugin;

    public ChatListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String content = PlainTextComponentSerializer.plainText().serialize(event.message());

        // The author carries their own report. Sending straight from the chat
        // thread is safe, and waiting for the main thread would lose the report
        // of a player who disconnects right after speaking.
        player.sendPluginMessage(plugin, ChatChannel.CHANNEL,
                ChatCodec.encodePlayerMessage(player.getUniqueId(), player.getName(), content));
    }
}
