package me.ganamaga.katharsis_paper;

import me.ganamaga.katharsis_paper.command.LanguageCommand;
import me.ganamaga.katharsis_paper.command.SettingsCommand;
import me.ganamaga.katharsis_paper.listener.AnnounceListener;
import me.ganamaga.katharsis_paper.listener.ChatListener;
import me.ganamaga.katharsis_paper.listener.LanguageMenuListener;
import me.ganamaga.katharsis_paper.listener.MuteListener;
import me.ganamaga.katharsis_paper.listener.SettingsMenuListener;
import me.ganamaga.katharsis_paper.menu.LanguageMenuView;
import me.ganamaga.katharsis_paper.menu.SettingsMenuView;
import me.ganamaga.katharsis_paper.mute.MuteRegistry;
import me.ganamaga.katharsis_paper.protocol.ChatChannel;
import me.ganamaga.katharsis_paper.protocol.LanguageChannel;
import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import me.ganamaga.katharsis_paper.protocol.PunishChannel;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Backend companion for the Katharsis proxy plugin.
 *
 * <p>Reimplements {@code /language} as a clickable selector. All player data and
 * every translated string live on the proxy; this plugin only draws the menu and
 * relays the choice, so the two can never disagree about a player's language.</p>
 *
 * <p>It also reports every chat message to the proxy, which keeps the chat log
 * and posts it to Discord. Messages from Discord are delivered in game by the
 * proxy itself; the companion only tells it this server's id.</p>
 *
 * <p>{@code /settings} gathers the language and whether messages from Discord
 * are shown in game. When the proxy says a player has joined the network for
 * the first time, the language selector opens for them on its own, followed by
 * the question about Discord chat.</p>
 *
 * <p>Mutes are issued on the proxy, which cannot block chat itself; it tells
 * this plugin who is muted here, and this plugin cancels their chat and the
 * commands they could talk through.</p>
 */
public final class KatharsisPaper extends JavaPlugin {

    /**
     * Ticks between the proxy asking for a new player's selector and opening
     * it, so it is not closed by the client's loading screen.
     */
    private static final long FIRST_JOIN_MENU_DELAY_TICKS = 20L;

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

        String serverId = getConfig().getString("server-id", "").strip();
        getServer().getMessenger().registerOutgoingPluginChannel(this, ChatChannel.CHANNEL);
        getServer().getPluginManager().registerEvents(new ChatListener(this, serverId), this);

        MuteListener muteListener = new MuteListener(this, new MuteRegistry());
        getServer().getMessenger().registerOutgoingPluginChannel(this, PunishChannel.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, PunishChannel.CHANNEL, muteListener);
        getServer().getPluginManager().registerEvents(muteListener, this);

        AnnounceListener announcer = new AnnounceListener(this, client, getPluginMeta().getVersion(), serverId);
        getServer().getPluginManager().registerEvents(announcer, this);

        LanguageCommand languageCommand =
                new LanguageCommand(this, client, new LanguageMenuView(), messages);
        SettingsMenuView settingsView = new SettingsMenuView();
        SettingsCommand settingsCommand = new SettingsCommand(this, client, settingsView, messages);
        registerCommand("language", languageCommand);
        registerCommand("settings", settingsCommand);

        getServer().getPluginManager().registerEvents(
                new LanguageMenuListener(this, client, messages, settingsCommand::openDiscordChatPrompt), this);
        getServer().getPluginManager().registerEvents(
                new SettingsMenuListener(this, client, settingsView, languageCommand, messages), this);

        // The proxy asks for this when a player joins the network for the
        // first time. Picking a language then leads on to the Discord chat
        // question.
        client.onOpenMenuRequest(player -> getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                languageCommand.open(player, true);
            }
        }, FIRST_JOIN_MENU_DELAY_TICKS));

        // Tell the proxy who we are straight away. On a cold start there is no
        // player yet to carry the message, so this reports that and the first
        // join sends it instead.
        announcer.announceOnStartup();
        // After a reload with players online, nobody is muted until the proxy says so.
        muteListener.resync();
    }

    @Override
    public void onDisable() {
        if (client != null) {
            client.cancelAll();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    private void registerCommand(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            // Only reachable if plugin.yml and this class disagree.
            getLogger().severe("/" + name + " is missing from plugin.yml; it is unavailable.");
            return;
        }
        command.setExecutor(executor);
    }
}
