package me.ganamaga.katharsis_proxy.punish.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.database.PlayerRepository;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.ActivePunishments;
import me.ganamaga.katharsis_proxy.punish.IpAddresses;
import me.ganamaga.katharsis_proxy.punish.PunishPermissions;
import me.ganamaga.katharsis_proxy.punish.PunishmentService;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.TargetResolver;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Keeps players under a network-wide ban off the proxy, and loads what is in
 * force against everyone else for the rest of their session.
 *
 * <p>The ban is checked twice. Before login, an offline-mode name is checked
 * by the account it stands for: this runs ahead of the login checks, so such
 * a banned player never costs a Mojang lookup or reaches the login void. A
 * premium name is left to the second check, since Mojang lets names change
 * hands and only the uuid is certain; so are bans on addresses, since staff
 * exempt from them are only known at login. At login, with the uuid final,
 * the account and address are checked and the session's punishments are
 * loaded.</p>
 */
public final class PunishmentLoginListener {

    /** Reloading a session gives up after this many changes in a row, which never happens outside a test. */
    private static final int MAX_RELOADS = 5;

    private final Logger logger;
    private final PunishmentService service;
    private final PlayerHistoryRepository history;
    private final PlayerRepository players;
    private final PunishmentText text;
    private final BooleanSupplier failClosed;

    public PunishmentLoginListener(Logger logger, PunishmentService service, PlayerHistoryRepository history,
                                   PlayerRepository players, PunishmentText text, BooleanSupplier failClosed) {
        this.logger = logger;
        this.service = service;
        this.history = history;
        this.players = players;
        this.text = text;
        this.failClosed = failClosed;
    }

    @Subscribe(order = PostOrder.EARLY)
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        String name = event.getUsername();
        return EventTask.async(() -> {
            try {
                UUID offlineAccount = offlineAccount(name);
                if (offlineAccount == null) {
                    return;
                }
                // Only bans on the account itself: staff exempt from bans on
                // an address they share cannot be told apart until login.
                Optional<Punishment> ban = PunishmentService.globalBan(
                        service.activeAgainst(offlineAccount, null), offlineAccount, true);
                if (ban.isPresent()) {
                    logger.info("Turned away {} before login: banned by #{}.", name, ban.get().id());
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(banScreen(offlineAccount, ban.get())));
                }
            } catch (SQLException e) {
                unavailable(name, e).ifPresent(reason ->
                        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(reason)));
            }
        });
    }

    /**
     * The account {@code name} is known as, if it is an offline-mode account,
     * whose uuid follows from the name. A premium account is left out: its
     * name could have been handed to someone else.
     */
    private UUID offlineAccount(String name) throws SQLException {
        UUID derived = TargetResolver.offlineUuid(name);
        return history.findByName(name).stream()
                .map(seen -> seen.uuid())
                .filter(derived::equals)
                .findFirst()
                .orElse(null);
    }

    @Subscribe(order = PostOrder.EARLY)
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        Player player = event.getPlayer();
        return EventTask.async(() -> {
            try {
                Optional<Punishment> ban = load(player);
                if (ban.isPresent()) {
                    logger.info("Turned away {} ({}): banned by #{}.",
                            player.getUsername(), player.getUniqueId(), ban.get().id());
                    event.setResult(ResultedEvent.ComponentResult.denied(banScreen(player.getUniqueId(), ban.get())));
                }
            } catch (SQLException e) {
                unavailable(player.getUsername(), e).ifPresent(reason ->
                        event.setResult(ResultedEvent.ComponentResult.denied(reason)));
            }
        });
    }

    /**
     * Something may have been issued against the player between loading their
     * session and their joining the proxy, too early to reach them either way.
     * If anything changed since, load again, and disconnect them if they turn
     * out to be banned.
     */
    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        Optional<ActivePunishments.Session> session = service.active().session(player.getUniqueId());
        if (session.isEmpty() || session.get().generation() == service.generation()) {
            return null;
        }
        return EventTask.async(() -> {
            try {
                for (int i = 0; i < MAX_RELOADS; i++) {
                    long before = service.generation();
                    Optional<Punishment> ban = load(player);
                    if (ban.isPresent()) {
                        player.disconnect(banScreen(player.getUniqueId(), ban.get()));
                        return;
                    }
                    if (service.generation() == before) {
                        return;
                    }
                }
            } catch (SQLException e) {
                logger.error("Could not reload the punishments of {}.", player.getUsername(), e);
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        service.active().remove(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    /**
     * Loads what is in force against {@code player} into their session.
     *
     * @return the network-wide ban keeping them out, if any; then nothing is loaded
     */
    private Optional<Punishment> load(Player player) throws SQLException {
        UUID uuid = player.getUniqueId();
        String ip = IpAddresses.of(player.getRemoteAddress()).orElse(null);
        boolean exempt = player.hasPermission(PunishPermissions.EXEMPT);
        long generation = service.generation();
        List<Punishment> active = service.activeAgainst(uuid, ip);
        if (exempt) {
            // Bans on an address they happen to share do not hold them.
            active = active.stream().filter(p -> uuid.equals(p.targetUuid())).toList();
        }
        Optional<Punishment> ban = PunishmentService.globalBan(active, uuid, false);
        if (ban.isEmpty()) {
            service.active().put(uuid, new ActivePunishments.Session(player, ip, active, generation, exempt));
        }
        return ban;
    }

    /** The reason to turn a player away with while bans cannot be checked, if they are to be. */
    private Optional<Component> unavailable(String name, SQLException e) {
        if (failClosed.getAsBoolean()) {
            logger.error("Could not check the bans of {}; turned them away.", name, e);
            return Optional.of(text.messages().render(defaultLanguage(), MessageKey.PUNISH_UNAVAILABLE_SCREEN));
        }
        logger.error("Could not check the bans of {}; let them in unchecked.", name, e);
        return Optional.empty();
    }

    /**
     * Rendered in the player's stored language: at this point their language
     * has not been cached yet.
     */
    private Component banScreen(UUID uuid, Punishment ban) {
        return text.banScreen(languageOf(uuid), ban, service.clock().instant());
    }

    private String languageOf(UUID uuid) {
        if (uuid == null) {
            return defaultLanguage();
        }
        try {
            return players.find(uuid)
                    .map(me.ganamaga.katharsis_proxy.entity.Player::language)
                    .orElseGet(this::defaultLanguage);
        } catch (SQLException e) {
            return defaultLanguage();
        }
    }

    private String defaultLanguage() {
        return text.messages().registry().defaultLanguage();
    }
}
