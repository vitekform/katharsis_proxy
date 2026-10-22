package me.ganamaga.katharsis_neoforge;

import com.mojang.logging.LogUtils;
import me.ganamaga.katharsis_neoforge.chat.ChatReporter;
import me.ganamaga.katharsis_neoforge.command.CompanionCommands;
import me.ganamaga.katharsis_neoforge.config.CompanionSettings;
import me.ganamaga.katharsis_neoforge.gnlib.GnlibSupport;
import me.ganamaga.katharsis_neoforge.lock.Locks;
import me.ganamaga.katharsis_neoforge.menu.LanguageMenu;
import me.ganamaga.katharsis_neoforge.menu.SettingsMenu;
import me.ganamaga.katharsis_neoforge.mute.Mutes;
import me.ganamaga.katharsis_neoforge.protocol.AuthPayload;
import me.ganamaga.katharsis_neoforge.protocol.ProxyPayload;
import me.ganamaga.katharsis_neoforge.proxy.Announcer;
import me.ganamaga.katharsis_neoforge.proxy.ProxyClient;
import me.ganamaga.katharsis_neoforge.proxy.ProxyLink;
import me.ganamaga.katharsis_neoforge.update.ModUpdates;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Backend companion for the Katharsis Velocity proxy, on NeoForge servers.
 *
 * <p>Every player who joins is held in place, unable to do anything, until the
 * proxy says they have logged in. That lets players whose modded client cannot
 * enter the proxy's login void log in on this server instead.</p>
 *
 * <p>Beyond that it does what the Paper companion plugin does on Paper servers,
 * over the same channels: {@code /language} and {@code /settings} as chest
 * menus drawn from strings the proxy translates, the selector and the Discord
 * chat question on a new player's first join, every chat message reported to
 * the proxy for its log and Discord, and mutes enforced. Server-side only:
 * players do not need it installed.</p>
 *
 * <p>It keeps itself up to date from the release system; see
 * {@link ModUpdates}.</p>
 */
@Mod(value = KatharsisNeoForge.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class KatharsisNeoForge {

    public static final String MOD_ID = "katharsis";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks between the proxy asking for a new player's selector and opening
     * it, so it is not closed by the client's loading screen. Also how often a
     * player still logging in is checked again.
     */
    private static final int FIRST_JOIN_MENU_DELAY_TICKS = 20;

    private final Locks locks = new Locks();
    private final Delays delays = new Delays();

    public KatharsisNeoForge(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, AuthPayload::register);
        modBus.addListener(RegisterPayloadHandlersEvent.class, ProxyPayload::register);
        modBus.addListener(RegisterConfigurationTasksEvent.class, locks::onConfiguration);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, locks::onLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, locks::onLoggedOut);
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, locks::onDamage);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, locks::onTick);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, delays::onTick);

        setUpCompanion(modBus, container.getModInfo().getVersion().toString());

        ModUpdates.create(FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get(), FMLPaths.MODSDIR.get())
                .ifPresent(updates -> {
                    updates.start();
                    // Once stopped, nothing more is loaded from the jar being replaced.
                    NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> updates.stopAndInstall());
                });
    }

    /** What the Paper companion plugin does, on the same channels. */
    private void setUpCompanion(IEventBus modBus, String version) {
        Path settingsFile = FMLPaths.CONFIGDIR.get().resolve(CompanionSettings.FILE_NAME);
        CompanionSettings settings = loadSettings(settingsFile);
        CompanionMessages messages = new CompanionMessages(settings);
        String serverId = settings.serverId();

        ProxyLink link = new ProxyLink();
        // After the lock's, though either order works: the lock passes the proxy's messages on.
        modBus.addListener(RegisterConfigurationTasksEvent.class, link::onConfiguration);
        // First, so the replies to whatever the other listeners ask on join can be heard.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, PlayerEvent.PlayerLoggedInEvent.class,
                link::onLoggedIn);

        // GNLib's remote content behind the proxy, only when GNLib is installed:
        // its auth gate follows the login lock, and this server presents the
        // network's identity with a certificate from the proxy.
        GnlibSupport.install(locks, link, settings, settingsFile);

        ProxyClient client = new ProxyClient(link, settings.requestTimeoutSeconds());
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> client.cancelAll());

        Announcer announcer = new Announcer(client, version, serverId);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> announcer.onServerStarted());
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, announcer::onLoggedIn);

        // Muted chat is cancelled before anything else sees it, and so never reported.
        Mutes mutes = new Mutes(link);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ServerChatEvent.class, mutes::onChat);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, CommandEvent.class, mutes::onCommand);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, mutes::onLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, mutes::onLoggedOut);
        ChatReporter reporter = new ChatReporter(link, serverId);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerChatEvent.class, reporter::onChat);

        LanguageMenu[] languageMenu = new LanguageMenu[1];
        SettingsMenu settingsMenu = new SettingsMenu(client, messages, () -> languageMenu[0]);
        languageMenu[0] = new LanguageMenu(client, messages, settingsMenu::openDiscordChatPrompt);
        CompanionCommands commands = new CompanionCommands(messages,
                player -> languageMenu[0].open(player, false), settingsMenu::open);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, commands::register);

        // The proxy asks for this when a player joins the network for the
        // first time. Picking a language then leads on to the Discord chat
        // question.
        client.onOpenMenuRequest(player -> openFirstJoinMenu(player, languageMenu[0]));
    }

    /** Once the player is past the loading screen, and has logged in if they are held until then. */
    private void openFirstJoinMenu(ServerPlayer player, LanguageMenu languageMenu) {
        delays.later(FIRST_JOIN_MENU_DELAY_TICKS, () -> {
            if (player.hasDisconnected()) {
                return;
            }
            if (locks.isHeld(player)) {
                // A held player could neither click it nor close it.
                openFirstJoinMenu(player, languageMenu);
                return;
            }
            languageMenu.open(player, true);
        });
    }

    private static CompanionSettings loadSettings(Path file) {
        try {
            return CompanionSettings.load(file);
        } catch (IOException e) {
            LOGGER.warn("Could not read {} ({}); using the default companion settings.", file, e.getMessage());
            return CompanionSettings.defaults();
        }
    }
}
