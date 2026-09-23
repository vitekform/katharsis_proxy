package me.ganamaga.katharsis_proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.auth.AuthGateListener;
import me.ganamaga.katharsis_proxy.auth.AuthLimbo;
import me.ganamaga.katharsis_proxy.auth.AuthService;
import me.ganamaga.katharsis_proxy.auth.MojangProfiles;
import me.ganamaga.katharsis_proxy.auth.PasswordHasher;
import me.ganamaga.katharsis_proxy.auth.PasswordPolicy;
import me.ganamaga.katharsis_proxy.command.AuthCommand;
import me.ganamaga.katharsis_proxy.command.LinkCommand;
import me.ganamaga.katharsis_proxy.command.ServerSwitchCommand;
import me.ganamaga.katharsis_proxy.command.punish.AltsCommand;
import me.ganamaga.katharsis_proxy.command.punish.CheckCommand;
import me.ganamaga.katharsis_proxy.command.punish.HistoryCommand;
import me.ganamaga.katharsis_proxy.command.punish.IssueCommand;
import me.ganamaga.katharsis_proxy.command.punish.KickCommand;
import me.ganamaga.katharsis_proxy.command.punish.PunishContext;
import me.ganamaga.katharsis_proxy.command.punish.RevokeCommand;
import me.ganamaga.katharsis_proxy.command.punish.WarnsCommand;
import me.ganamaga.katharsis_proxy.config.AuthConfig;
import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.config.DiscordConfig;
import me.ganamaga.katharsis_proxy.config.MotdConfig;
import me.ganamaga.katharsis_proxy.config.MotdMode;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.database.AccountLinkRepository;
import me.ganamaga.katharsis_proxy.database.AuthAccountRepository;
import me.ganamaga.katharsis_proxy.database.ChatMessageRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.database.PunishmentRepository;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.discord.DiscordAccountLinking;
import me.ganamaga.katharsis_proxy.discord.DiscordBot;
import me.ganamaga.katharsis_proxy.discord.DiscordChatRelay;
import me.ganamaga.katharsis_proxy.discord.DiscordInboundChat;
import me.ganamaga.katharsis_proxy.discord.DiscordModerationAlert;
import me.ganamaga.katharsis_proxy.discord.DiscordPunishCommands;
import me.ganamaga.katharsis_proxy.discord.DiscordPunishmentLog;
import me.ganamaga.katharsis_proxy.i18n.LanguageRegistry;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.link.AccountLinkService;
import me.ganamaga.katharsis_proxy.listener.AccountLinkJoinListener;
import me.ganamaga.katharsis_proxy.listener.FirstJoinMenuListener;
import me.ganamaga.katharsis_proxy.listener.PlayerJoinListener;
import me.ganamaga.katharsis_proxy.messaging.ChatChannel;
import me.ganamaga.katharsis_proxy.messaging.ChatChannelListener;
import me.ganamaga.katharsis_proxy.messaging.CompanionRegistry;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannel;
import me.ganamaga.katharsis_proxy.messaging.LanguageChannelListener;
import me.ganamaga.katharsis_proxy.messaging.PunishChannel;
import me.ganamaga.katharsis_proxy.moderation.ChatModerator;
import me.ganamaga.katharsis_proxy.moderation.ModerationAlerts;
import me.ganamaga.katharsis_proxy.moderation.ModerationFactory;
import me.ganamaga.katharsis_proxy.moderation.ModerationNotice;
import me.ganamaga.katharsis_proxy.moderation.PlayerModerationNotice;
import me.ganamaga.katharsis_proxy.motd.MotdLibrary;
import me.ganamaga.katharsis_proxy.motd.MotdPingListener;
import me.ganamaga.katharsis_proxy.motd.generator.BannerGenerator;
import me.ganamaga.katharsis_proxy.motd.generator.MineSkinUploader;
import me.ganamaga.katharsis_proxy.motd.generator.TextureCache;
import me.ganamaga.katharsis_proxy.permission.LuckPermsLookup;
import me.ganamaga.katharsis_proxy.permission.OnlinePermissionLookup;
import me.ganamaga.katharsis_proxy.permission.PermissionLookup;
import me.ganamaga.katharsis_proxy.player.PlayerDiscordChatCache;
import me.ganamaga.katharsis_proxy.player.PlayerLanguageCache;
import me.ganamaga.katharsis_proxy.punish.ActivePunishments;
import me.ganamaga.katharsis_proxy.punish.AltDetector;
import me.ganamaga.katharsis_proxy.punish.AutoWarn;
import me.ganamaga.katharsis_proxy.punish.IpAddresses;
import me.ganamaga.katharsis_proxy.punish.JoinInspector;
import me.ganamaga.katharsis_proxy.punish.MuteEnforcer;
import me.ganamaga.katharsis_proxy.punish.PermissionHierarchy;
import me.ganamaga.katharsis_proxy.punish.PunishmentService;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.ScopeResolver;
import me.ganamaga.katharsis_proxy.punish.ServerBans;
import me.ganamaga.katharsis_proxy.punish.StaffNotifier;
import me.ganamaga.katharsis_proxy.punish.Target;
import me.ganamaga.katharsis_proxy.punish.TargetResolver;
import me.ganamaga.katharsis_proxy.punish.TemplateRegistry;
import me.ganamaga.katharsis_proxy.punish.WarnEscalation;
import me.ganamaga.katharsis_proxy.punish.VelocityEnforcer;
import me.ganamaga.katharsis_proxy.punish.listener.PunishmentLoginListener;
import me.ganamaga.katharsis_proxy.punish.listener.PunishmentServerListener;
import me.ganamaga.katharsis_proxy.punish.listener.SessionRecorder;
import me.ganamaga.katharsis_proxy.util.DebugLog;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Katharsis_proxy {

    /** Every server switch command shares this cooldown. (global doesn't make sense) */
    /** Well technically it would make sense but I just chose to make it per server :D Get rekt */
    private static final Duration COMMAND_COOLDOWN = Duration.ofMinutes(1);

    /** How often expired language cache entries are reclaimed. */
    // You are allowed to guess how long is 1 :)
    private static final long CACHE_SWEEP_MINUTES = 1L;

    /** LimboAPI's plugin id, which hosts the login void. */
    public static final String LIMBOAPI_ID = "limboapi";

    /** LuckPerms' plugin id; optional, see velocity-plugin.json. */
    public static final String LUCKPERMS_ID = "luckperms";

    /**
     * Passwords checked at once. Each Argon2 hash holds its configured memory
     * while it runs, so this bounds what a crowd of logins can take.
     */
    private static final int AUTH_THREADS = 2;

    /** Logins waiting for a thread; past this, players are asked to try again. */
    private static final int AUTH_QUEUE = 64;

    // fuck you velocity, provide normal color API for once
    public static final String RESET  = "\u001B[0m";
    public static final String RED    = "\u001B[31m";
    public static final String GREEN  = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxy;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private final List<ServerSwitchCommand> serverSwitchCommands = new ArrayList<>();
    private final PlayerLanguageCache languageCache = new PlayerLanguageCache();
    private final PlayerDiscordChatCache discordChatCache = new PlayerDiscordChatCache();

    private ConfigManager configManager;

    /** {@link DebugLog#DISABLED} until the config has been read, and after it
     * when debug-log is off. */
    private DebugLog debugLog = DebugLog.DISABLED;

    private LanguageRegistry languageRegistry;
    private Messages messages;
    private DatabaseManager database;
    private PlayerRepository players;
    private ChatMessageRepository chatMessages;
    private AccountLinkRepository accountLinkRepository;
    private AuthAccountRepository authAccounts;
    private PlayerHistoryRepository playerHistory;
    private PunishmentRepository punishments;
    private PunishmentService punishmentService;
    private MuteEnforcer muteEnforcer;
    private AutoWarn autoWarn;
    private PermissionLookup permissionLookup;
    private SessionRecorder sessionRecorder;
    private MotdLibrary motdLibrary;
    private DiscordBot discordBot;
    private AccountLinkService accountLinks;
    private DiscordAccountLinking discordLinking;
    private LinkCommand linkCommand;
    private CompanionRegistry companions;

    /** Null while logging in is off. */
    private ExecutorService authExecutor;

    /** Null unless LimboAPI is installed and logging in is on. */
    private AuthLimbo authLimbo;

    /** Null while chat moderation is off. */
    private ChatModerator chatModerator;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info(YELLOW + "Loading Katharsis Proxy...");
        loadConfig();
        openDebugLog();
        loadLanguages();
        setUpDatabase();
        // Before the commands and plugin messaging, which hand work to the bot.
        startDiscordBot();
        setUpAccountLinking();
        setUpAuth();
        // Before the chat channel, which hands every reported message to it.
        setUpModeration();
        setUpPunishments();
        registerCommands();
        registerPluginMessaging();
        registerMotd();
        scheduleCacheSweep();
        logger.info(GREEN + "Katharsis Proxy loaded successfully.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        languageCache.clear();
        discordChatCache.clear();
        if (accountLinks != null) {
            accountLinks.clearCache();
        }
        // Before the database closes, in case queued tasks still read from it.
        if (discordBot != null) {
            discordBot.stop();
        }
        if (authLimbo != null) {
            authLimbo.dispose();
        }
        if (authExecutor != null) {
            authExecutor.shutdownNow();
        }
        if (database != null) {
            database.close();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // Drop the player's cooldowns so the maps do not grow without bound.
        for (ServerSwitchCommand command : serverSwitchCommands) {
            command.cooldowns().clear(playerId);
        }
        if (linkCommand != null) {
            linkCommand.cooldowns().clear(playerId);
        }
        if (autoWarn != null) {
            autoWarn.cooldowns().clear(playerId);
        }
        // Start the retention window. This lives here rather than in the join
        // listener so entries are still reclaimed when the database is down and
        // that listener was never registered.
        languageCache.expireLater(playerId);
        discordChatCache.expireLater(playerId);
        if (accountLinks != null) {
            accountLinks.expireLater(playerId);
        }
    }

    /** Reads config.yml. On failure the built-in defaults are used instead. */
    private void loadConfig() {
        configManager = new ConfigManager(logger, dataDirectory);
        try {
            configManager.load();
            logger.info("Loaded configuration from {}.", configManager.configPath());
        } catch (ConfigurateException e) {
            logger.error(RED + "Could not load config.yml; continuing with the built-in defaults.", e);
        }
    }

    /**
     * Opens debug.log, when it is turned on. Done after the config is read and
     * before anything that writes to it, so a failure here costs nothing more
     * than the file.
     */
    private void openDebugLog() {
        debugLog = DebugLog.create(logger, dataDirectory, configManager.config().debugLog());
        if (debugLog.isEnabled()) {
            logger.info("Writing OpenAI moderation calls to {}.", debugLog.path());
        } else {
            logger.info("debug-log is off; nothing is written to {}.", DebugLog.FILE_NAME);
        }
    }

    /**
     * Writes out the bundled language files and reads every language present.
     * On failure messages fall back to their keys, which is ugly but harmless.
     */
    private void loadLanguages() {
        languageRegistry = new LanguageRegistry(logger, dataDirectory);
        try {
            languageRegistry.load(configManager.config().defaultLanguage());
        } catch (ConfigurateException e) {
            logger.error(RED + "Could not load the language files; messages will show their keys.", e);
        }
        messages = new Messages(languageRegistry, languageCache);
    }

    /**
     * Opens the connection pool and starts persisting players. A failure here is
     * logged and leaves the rest of the plugin working, so the proxy still boots
     * without a reachable database.
     */
    private void setUpDatabase() {
        database = new DatabaseManager(logger, dataDirectory);
        players = new PlayerRepository(database);
        chatMessages = new ChatMessageRepository(database);
        accountLinkRepository = new AccountLinkRepository(database);
        authAccounts = new AuthAccountRepository(database);
        playerHistory = new PlayerHistoryRepository(database);
        punishments = new PunishmentRepository(database);
        try {
            database.start(configManager.config().database());
            players.createTable();
            chatMessages.createTable();
            accountLinkRepository.createTables();
            authAccounts.createTable();
            playerHistory.createTables();
            punishments.createTable();
        } catch (SQLException e) {
            logger.error(RED + "Could not open the database; player data will not be stored.", e);
            database.close();
            return;
        }

        FirstJoinMenuListener firstJoinMenu = new FirstJoinMenuListener(logger);
        proxy.getEventManager().register(this, firstJoinMenu);
        proxy.getEventManager().register(this,
                new PlayerJoinListener(logger, languageRegistry, database, players, languageCache,
                        discordChatCache, firstJoinMenu));
        sessionRecorder = new SessionRecorder(logger, playerHistory, Clock.systemUTC());
        proxy.getEventManager().register(this, sessionRecorder);
        logger.info(GREEN + "Player entities will be created on first join.");
    }

    /**
     * Sets up bans, mutes, kicks and warnings: the checks at login, their
     * enforcement, and the staff commands. Needs the database; without it
     * nothing can be issued or checked, so nothing is registered.
     */
    private void setUpPunishments() {
        PunishmentsConfig config = configManager.config().punishments();
        if (!config.enabled()) {
            logger.info("Punishments are disabled in {}.", configManager.configPath().getFileName());
            return;
        }
        if (!database.isAvailable()) {
            logger.error(RED + "Punishments are unavailable: the database could not be opened.");
            return;
        }
        Clock clock = Clock.systemUTC();
        Set<String> trusted = config.trustedProxyIps().stream()
                .map(IpAddresses::parseLiteral)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
        permissionLookup = permissionLookup();
        punishmentService = new PunishmentService(logger, punishments, new ActivePunishments(),
                new PermissionHierarchy(permissionLookup), trusted::contains, clock);
        PunishmentText text = new PunishmentText(messages,
                () -> configManager.config().punishments().appealUrl());
        ScopeResolver scopes = new ScopeResolver(config.serverGroups());
        config.serverGroups().forEach((group, members) -> members.stream()
                .filter(member -> proxy.getServer(member).isEmpty())
                .forEach(member -> logger.warn("punishments.server-groups.{} lists '{}', which is not a server "
                        + "in velocity.toml.", group, member)));
        ServerBans serverBans = new ServerBans(proxy, punishmentService, scopes, text);
        punishmentService.addObserver(new VelocityEnforcer(proxy, logger, text, clock, serverBans));
        StaffNotifier staff = new StaffNotifier(proxy, text);
        punishmentService.addObserver(staff);
        punishmentService.addObserver(new DiscordPunishmentLog(logger, discordBot,
                () -> configManager.config().punishments(),
                () -> configManager.config().moderation().staffChannelId()));
        muteEnforcer = new MuteEnforcer(proxy, logger, punishmentService, scopes, text,
                () -> configManager.config().punishments());
        punishmentService.addObserver(muteEnforcer);
        proxy.getChannelRegistrar().register(PunishChannel.IDENTIFIER);
        proxy.getEventManager().register(this, muteEnforcer);
        // Also used by plugin messaging, set up later; commands warn when a
        // muted player's server has no companion to enforce the mute.
        companions = new CompanionRegistry(logger);

        proxy.getEventManager().register(this, new PunishmentLoginListener(logger, punishmentService,
                playerHistory, players, text, () -> configManager.config().punishments().failClosed()));
        proxy.getEventManager().register(this, new PunishmentServerListener(logger, serverBans));

        TargetResolver targets = new TargetResolver(
                name -> proxy.getPlayer(name).map(player -> new Target(player.getUniqueId(), player.getUsername(),
                        IpAddresses.of(player.getRemoteAddress()).orElse(null))),
                playerHistory, authAccounts, !proxy.getConfiguration().isOnlineMode());
        TemplateRegistry templates = TemplateRegistry.load(config.templates(), logger::warn);
        WarnEscalation escalation = new WarnEscalation(logger, punishmentService, templates,
                WarnEscalation.load(config.warns().escalation(), templates, logger::warn), scopes,
                this::serverNames);
        // Last, so everyone hears of a warning before of what it escalated to.
        punishmentService.addObserver(escalation);
        autoWarn = new AutoWarn(logger, punishmentService, templates, () -> configManager.config().punishments(),
                task -> proxy.getScheduler().buildTask(this, task).schedule(), this::serverNames);

        AltDetector alts = new AltDetector(playerHistory, punishmentService,
                () -> configManager.config().punishments().alts());
        sessionRecorder.addInspector(new JoinInspector(logger, punishmentService, alts, staff,
                () -> configManager.config().punishments().alts()));

        PunishContext context = new PunishContext(this, proxy, logger, punishmentService, targets, playerHistory,
                scopes, text, () -> configManager.config().punishments(), companions, templates, escalation);
        DiscordPunishCommands discordCommands = new DiscordPunishCommands(logger, discordBot, messages,
                () -> configManager.config().discord(), () -> configManager.config().punishments(), accountLinks,
                permissionLookup, new DiscordPunishCommands.Punishments(punishmentService, targets, scopes, templates,
                text, this::serverNames, uuid -> proxy.getPlayer(uuid).isPresent()));
        if (discordCommands.register()) {
            logger.info("Staff can punish from Discord with /punish.");
        }

        registerStaffCommand("ban", new IssueCommand(context, "ban", PunishmentType.BAN,
                IssueCommand.Variant.PERMANENT));
        registerStaffCommand("tempban", new IssueCommand(context, "tempban", PunishmentType.BAN,
                IssueCommand.Variant.TEMPORARY));
        registerStaffCommand("banip", new IssueCommand(context, "banip", PunishmentType.BAN,
                IssueCommand.Variant.ADDRESS), "ipban");
        registerStaffCommand("unban", new RevokeCommand(context, "unban", PunishmentType.BAN), "pardon");
        registerStaffCommand("mute", new IssueCommand(context, "mute", PunishmentType.MUTE,
                IssueCommand.Variant.PERMANENT));
        registerStaffCommand("tempmute", new IssueCommand(context, "tempmute", PunishmentType.MUTE,
                IssueCommand.Variant.TEMPORARY));
        registerStaffCommand("muteip", new IssueCommand(context, "muteip", PunishmentType.MUTE,
                IssueCommand.Variant.ADDRESS), "ipmute");
        registerStaffCommand("unmute", new RevokeCommand(context, "unmute", PunishmentType.MUTE));
        registerStaffCommand("warn", new IssueCommand(context, "warn", PunishmentType.WARN,
                IssueCommand.Variant.PERMANENT));
        registerStaffCommand("unwarn", new RevokeCommand(context, "unwarn", PunishmentType.WARN), "delwarn");
        registerStaffCommand("warns", new WarnsCommand(context));
        registerStaffCommand("punish", new IssueCommand(context, "punish", null, IssueCommand.Variant.PERMANENT));
        registerStaffCommand("kick", new KickCommand(context));
        registerStaffCommand("history", new HistoryCommand(context));
        registerStaffCommand("check", new CheckCommand(context), "lookup");
        registerStaffCommand("alts", new AltsCommand(context, alts));
        logger.info(GREEN + "Punishments enabled: /ban, /tempban, /banip, /unban, /mute, /tempmute, /muteip, "
                + "/unmute, /warn, /unwarn, /warns, /punish, /kick, /history, /check, /alts. Mutes are enforced on servers "
                + "running the companion.");
        logger.info("{} punishment template(s), {} warning escalation step(s); automatic warnings for flagged "
                + "chat are {}.", templates.names().size(), escalation.steps().size(),
                config.automod().enabled() ? "on" : "off");
    }

    /**
     * LuckPerms if it is installed, which can answer for offline players and
     * knows staff weights; otherwise only players online can be asked about.
     */
    private PermissionLookup permissionLookup() {
        if (proxy.getPluginManager().isLoaded(LUCKPERMS_ID)) {
            logger.info("Using LuckPerms for the permissions and ranks of offline players.");
            return new LuckPermsLookup(logger);
        }
        logger.warn("LuckPerms is not installed: the permissions of offline players cannot be checked, so they "
                + "are never exempt and have no rank, and Discord staff must be online in game.");
        return new OnlinePermissionLookup(proxy);
    }

    private Collection<String> serverNames() {
        return proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()).toList();
    }

    private void registerStaffCommand(String name, SimpleCommand command, String... aliases) {
        CommandManager commandManager = proxy.getCommandManager();
        commandManager.register(commandManager.metaBuilder(name).aliases(aliases).plugin(this).build(), command);
    }

    private void registerCommands() {
        registerServerSwitchCommand("lobby");
        registerServerSwitchCommand("lifesteal");
        logger.info("Registered {} server switch command(s) with a {}s cooldown.",
                serverSwitchCommands.size(), COMMAND_COOLDOWN.toSeconds());

        // No /language here. The companion plugin owns the selector, and two
        // commands on the same name would shadow each other unpredictably
        // depending on which registered last.
        logger.info("No /language is registered on the proxy; the companion plugin provides the selector.");

        CommandManager commandManager = proxy.getCommandManager();
        linkCommand = new LinkCommand(this, proxy, logger, messages, accountLinks, discordLinking);
        commandManager.register(commandManager.metaBuilder(LinkCommand.NAME).plugin(this).build(), linkCommand);
        logger.info("Registered /{} for linking Minecraft accounts to Discord.", LinkCommand.NAME);
    }

    /**
     * Sets up linking Minecraft accounts to Discord: the cache loaded on join,
     * and /link on Discord. Set up even with the database down, so /link
     * answers that linking is unavailable instead of not existing.
     */
    private void setUpAccountLinking() {
        accountLinks = new AccountLinkService(database, accountLinkRepository);
        discordLinking = new DiscordAccountLinking(logger, discordBot, accountLinks, messages,
                () -> configManager.config().discord());
        if (database.isAvailable()) {
            proxy.getEventManager().register(this, new AccountLinkJoinListener(logger, accountLinks));
        }
        discordLinking.register();
    }

    /**
     * Makes players without a Mojang account log in with a password, in an
     * empty LimboAPI world, before they reach any server. Fails closed: without
     * LimboAPI, or without the database, such players are refused at the door
     * rather than let in unchecked.
     */
    private void setUpAuth() {
        AuthConfig config = configManager.config().auth();
        if (!config.enabled()) {
            logger.warn(RED + "auth.enabled is false: on an offline-mode proxy anyone can join under any name.");
            return;
        }

        AtomicInteger threads = new AtomicInteger();
        authExecutor = new ThreadPoolExecutor(AUTH_THREADS, AUTH_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(AUTH_QUEUE), runnable -> {
                    Thread thread = new Thread(runnable, "katharsis-auth-" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        AuthService auth = new AuthService(logger, database, authAccounts,
                new PasswordHasher(config.argon2()),
                new PasswordPolicy(config.minPasswordLength(), config.maxPasswordLength()),
                new MojangProfiles(logger, config.mojangUrl(), Duration.ofMillis(config.mojangTimeoutMs())),
                config.premiumSkip(), Clock.systemUTC());

        // Asked of Velocity rather than of AuthLimbo, which cannot even be loaded without LimboAPI.
        boolean limboInstalled = proxy.getPluginManager().isLoaded(LIMBOAPI_ID);
        if (limboInstalled) {
            authLimbo = new AuthLimbo(this, proxy, logger, auth, messages, config, authExecutor);
            authLimbo.start();
            logger.info(GREEN + "Players without a Mojang account log in from an empty void.");
        } else {
            logger.warn(RED + "LimboAPI is not installed, so players without a Mojang account have nowhere to "
                    + "log in and will be refused. Install it from https://github.com/Elytrium/LimboAPI");
        }
        proxy.getEventManager().register(this, new AuthGateListener(proxy, logger, auth, messages, limboInstalled));

        if (proxy.getConfiguration().isOnlineMode()) {
            logger.info("The proxy is in online mode, so Mojang logs everyone in and no password is asked for.");
        } else if (config.premiumSkip()) {
            logger.info("Names held by a Mojang account are sent through Mojang's login and skip the password.");
        }

        CommandManager commandManager = proxy.getCommandManager();
        commandManager.register(commandManager.metaBuilder(AuthCommand.NAMES.getFirst())
                .aliases(AuthCommand.NAMES.subList(1, AuthCommand.NAMES.size()).toArray(String[]::new))
                .plugin(this)
                .build(), new AuthCommand(messages));
        logger.info("Registered /login and /register.");
    }

    /**
     * Compiles the chat filters. With moderation off, or with nothing
     * configured to check with, this leaves {@link #chatModerator} null and
     * reported chat is relayed and logged exactly as it was before.
     */
    private void setUpModeration() {
        chatModerator = ModerationFactory.create(logger, configManager.config().moderation(), debugLog);
        if (chatModerator == null) {
            return;
        }
        if (configManager.config().moderation().staffChannelId() <= 0L) {
            logger.info("No moderation.staff-channel-id is set; held-back messages are only logged and stored.");
        }
    }

    /** Where held-back messages are reported, which is Discord or nowhere. */
    private ModerationAlerts moderationAlerts() {
        if (chatModerator == null) {
            return ModerationAlerts.NONE;
        }
        ModerationAlerts discord =
                new DiscordModerationAlert(logger, discordBot, () -> configManager.config().moderation());
        return autoWarn == null ? discord : ModerationAlerts.both(discord, autoWarn);
    }

    /** Whether the author of a held-back message is told, and how. */
    private ModerationNotice moderationNotice() {
        return chatModerator != null && configManager.config().moderation().warnPlayer()
                ? new PlayerModerationNotice(proxy, messages)
                : ModerationNotice.NONE;
    }

    /**
     * Opens the language and chat channels to backend servers. Registered even
     * when the database is down, so language callers get an explicit error
     * instead of silence and chat reports are still kept away from clients.
     */
    private void registerPluginMessaging() {
        if (companions == null) {
            companions = new CompanionRegistry(logger);
        }
        proxy.getChannelRegistrar().register(LanguageChannel.IDENTIFIER);
        proxy.getEventManager().register(this, new LanguageChannelListener(
                proxy, logger, languageRegistry, messages, database, players, languageCache, discordChatCache,
                companions));
        logger.info("Serving language requests on the '{}' plugin message channel.",
                LanguageChannel.IDENTIFIER.getId());

        proxy.getChannelRegistrar().register(ChatChannel.IDENTIFIER);
        DiscordChatRelay discordRelay = new DiscordChatRelay(logger, discordBot, () -> configManager.config().discord());
        proxy.getEventManager().register(this, new ChatChannelListener(logger, database, chatMessages, discordRelay,
                companions, chatModerator, moderationAlerts(), moderationNotice()));
        logger.info("Logging chat reported on the '{}' plugin message channel.",
                ChatChannel.IDENTIFIER.getId());
        int relayed = configManager.config().discord().chatChannels().size();
        if (discordBot.isAccepting() && relayed > 0) {
            logger.info("Posting chat from {} server(s) to Discord.", relayed);
        }

        DiscordInboundChat inbound = new DiscordInboundChat(logger, proxy, discordBot, accountLinks, messages,
                companions, discordChatCache, () -> configManager.config().discord());
        if (inbound.register() && relayed > 0) {
            logger.info("Delivering messages from the Discord chat channels in game.");
        }
    }

    /**
     * Takes over the server list ping with the banner MOTD, when it is turned
     * on. Everything here is optional: with the feature off, or with no banner
     * files present, the proxy keeps answering pings from velocity.toml.
     */
    private void registerMotd() {
        MotdConfig motd = configManager.config().motd();
        if (!motd.enabled()) {
            logger.info("The banner MOTD is off; pings are answered from velocity.toml.");
            return;
        }

        motdLibrary = new MotdLibrary(logger, dataDirectory);
        motdLibrary.load(motd.count());
        if (motdLibrary.isEmpty() && !motd.generator().enabled()) {
            logger.warn(RED +"The banner MOTD is on but no banner loaded from {}; every ping will use the fallback.",
                    dataDirectory);
        }

        // The config is passed as a supplier so a later reload is picked up
        // without re-registering the listener.
        proxy.getEventManager().register(this,
                new MotdPingListener(() -> configManager.config().motd(), motdLibrary));

        // Keyed on the configured count rather than what loaded: banners generated
        // in the background arrive after this point, and advancing over fewer
        // than two is harmless.
        if (motd.mode() == MotdMode.ROLLING && motd.count() > 1 && motd.rollingIntervalSeconds() > 0L) {
            proxy.getScheduler().buildTask(this, motdLibrary::advance)
                    .repeat(motd.rollingIntervalSeconds(), TimeUnit.SECONDS)
                    .schedule();
            logger.info("Serving {} banner MOTD(s), rotating every {}s.",
                    motdLibrary.size(), motd.rollingIntervalSeconds());
        } else {
            logger.info("Serving {} banner MOTD(s) to clients on protocol {}-{}.",
                    motdLibrary.size(), motd.minProtocol(), motd.maxProtocol());
        }

        if (motd.generator().enabled()) {
            generateBanners(motd);
        }
    }

    /**
     * Builds banner JSON from motd.png and friends, off the startup thread.
     * MineSkin allows roughly one upload every six seconds, so a new image can
     * take a minute or more; until it is done pings get whatever was already
     * loaded, or the fallback.
     */
    private void generateBanners(MotdConfig motd) {
        proxy.getScheduler().buildTask(this, () -> {
            try {
                BannerGenerator generator = new BannerGenerator(logger,
                        MineSkinUploader.fromConfig(logger, motd.generator()),
                        TextureCache.load(dataDirectory.resolve(BannerGenerator.CACHE_FILE_NAME)));
                if (generator.refresh(dataDirectory, motd.count()) > 0) {
                    motdLibrary.load(motd.count());
                    logger.info("Now serving {} banner MOTD(s) after generating from images.", motdLibrary.size());
                }
            } catch (IOException e) {
                logger.error(RED + "Could not read {}; no banners were generated from images.",
                        BannerGenerator.CACHE_FILE_NAME, e);
            } catch (IllegalArgumentException e) {
                logger.error(RED + "motd.generator.mineskin-url is not a valid URL; no banners were generated.", e);
            } catch (InterruptedException e) {
                // The proxy is shutting down. Uploaded tiles are already cached.
                Thread.currentThread().interrupt();
                return;
            }
            if (motdLibrary.isEmpty()) {
                logger.warn(RED + "The banner MOTD is on but no banner loaded from {}; every ping will use the fallback.",
                        dataDirectory);
            }
        }).schedule();
    }

    /**
     * Logs the Discord bot in on its own thread. The bot is created even when
     * it is off, so {@link #discordBot()} is always usable and simply rejects
     * tasks.
     */
    private void startDiscordBot() {
        discordBot = new DiscordBot(logger);
        DiscordConfig discord = configManager.config().discord();
        if (!discord.enabled()) {
            logger.info("The Discord bot is off.");
            return;
        }
        if (discord.token().isBlank()) {
            logger.warn(RED + "The Discord bot is on but discord.token is empty; it will not start.");
            return;
        }
        discordBot.start(discord.token().strip(), discord.relayToMinecraft());
    }

    /**
     * The Discord bot. Submit work to it from any thread; it runs the tasks in
     * order on its own thread.
     */
    public DiscordBot discordBot() {
        return discordBot;
    }

    /**
     * Reclaims cache entries whose retention window has passed. Lookups already
     * honour the expiry, so this only frees memory.
     */
    private void scheduleCacheSweep() {
        proxy.getScheduler().buildTask(this, () -> {
            discordChatCache.evictExpired();
            if (punishmentService != null) {
                muteEnforcer.expired(punishmentService.active().evictExpired(punishmentService.clock().instant()));
            }
            int evicted = languageCache.evictExpired();
            if (evicted > 0) {
                logger.debug("Dropped {} cached language(s); {} remaining.", evicted, languageCache.size());
            }
            int links = accountLinks.evictExpired();
            if (links > 0) {
                logger.debug("Dropped {} cached account link(s).", links);
            }
        }).repeat(CACHE_SWEEP_MINUTES, TimeUnit.MINUTES).schedule();
        logger.info("Caching player languages for {} minute(s) after disconnect.",
                PlayerLanguageCache.RETENTION.toMinutes());
        logger.info("Caching account links for {} minute(s) after disconnect.",
                AccountLinkService.CACHE_RETENTION.toMinutes());
    }

    private void registerServerSwitchCommand(String serverName) {
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder(serverName)
                .plugin(this)
                .build();
        ServerSwitchCommand command =
                new ServerSwitchCommand(proxy, logger, messages, serverName, COMMAND_COOLDOWN);
        commandManager.register(meta, command);
        serverSwitchCommands.add(command);

        if (proxy.getServer(serverName).isEmpty()) {
            logger.warn(RED + "Registered /{} but no server named '{}' exists in velocity.toml.", serverName, serverName);
        } else {
            logger.info(GREEN + "Registered /{} -> server '{}'.", serverName, serverName);
        }
    }
}
