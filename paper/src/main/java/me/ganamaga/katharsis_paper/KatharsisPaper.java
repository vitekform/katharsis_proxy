package me.ganamaga.katharsis_paper;

import me.ganamaga.katharsis_paper.command.LanguageCommand;
import me.ganamaga.katharsis_paper.listener.AnnounceListener;
import me.ganamaga.katharsis_paper.listener.LanguageMenuListener;
import me.ganamaga.katharsis_paper.menu.LanguageMenuView;
import me.ganamaga.katharsis_paper.protocol.LanguageChannel;
import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Backend companion for the Katharsis proxy plugin.
 *
 * <p>Reimplements {@code /language} as a clickable selector. All player data and
 * every translated string live on the proxy; this plugin only draws the menu and
 * relays the choice, so the two can never disagree about a player's language.</p>
 */
public final class KatharsisPaper extends JavaPlugin {

    /** Seconds to wait for the proxy before telling the player it is unreachable. */
    private static final long DEFAULT_TIMEOUT_SECONDS = 5L;

    private ProxyClient client;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CompanionMessages messages = new CompanionMessages(getConfig());
        long timeout = getConfig().getLong("request-timeout-seconds", DEFAULT_TIMEOUT_SECONDS);

        client = new ProxyClient(this, timeout);
        getServer().getMessenger().registerOutgoingPluginChannel(this, LanguageChannel.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, LanguageChannel.CHANNEL, client);

        getServer().getPluginManager().registerEvents(
                new LanguageMenuListener(this, client, messages), this);

        AnnounceListener announcer = new AnnounceListener(this, client, getPluginMeta().getVersion());
        getServer().getPluginManager().registerEvents(announcer, this);

        registerLanguageCommand(messages);

        // Tell the proxy who we are straight away. On a cold start there is no
        // player yet to carry the message, so this reports that and the first
        // join sends it instead.
        announcer.announceOnStartup();
    }

    @Override
    public void onDisable() {
        if (client != null) {
            client.cancelAll();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    private void registerLanguageCommand(CompanionMessages messages) {
        PluginCommand command = getCommand("language");
        if (command == null) {
            // Only reachable if plugin.yml and this class disagree.
            getLogger().severe("/language is missing from plugin.yml; the selector is unavailable.");
            return;
        }
        command.setExecutor(new LanguageCommand(this, client, new LanguageMenuView(), messages));
    }
}
