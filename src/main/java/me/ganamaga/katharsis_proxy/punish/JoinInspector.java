package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.config.AltsConfig;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.listener.SessionRecorder;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Looks each new session over for accounts sharing its addresses.
 *
 * <p>An account joining from an address shared with an account under a
 * network-wide ban may be evading it: staff are told, or with
 * {@code alts.evasion: ban} the new account is banned for as long as the
 * original has left. Staff are also told when a player joins with active
 * warnings or banned accounts on their addresses. Players exempt from
 * punishment are not looked at.</p>
 */
public final class JoinInspector implements SessionRecorder.Inspector {

    public static final String ISSUER_NAME = "Evasion";

    private final Logger logger;
    private final PunishmentService service;
    private final AltDetector alts;
    private final StaffNotifier staff;
    private final Supplier<AltsConfig> config;

    public JoinInspector(Logger logger, PunishmentService service, AltDetector alts, StaffNotifier staff,
                         Supplier<AltsConfig> config) {
        this.logger = logger;
        this.service = service;
        this.alts = alts;
        this.staff = staff;
        this.config = config;
    }

    @Override
    public void inspect(Player player, String ip) throws SQLException {
        AltsConfig settings = config.get();
        if (player.hasPermission(PunishPermissions.EXEMPT)
                || (settings.evasion() == AltsConfig.EvasionAction.IGNORE && !settings.joinAlerts())) {
            return;
        }
        List<AltDetector.Alt> found = alts.alts(player.getUniqueId());
        List<AltDetector.Alt> evaded = found.stream().filter(alt -> alt.globalBan().isPresent()).toList();

        if (!evaded.isEmpty() && settings.evasion() != AltsConfig.EvasionAction.IGNORE) {
            AltDetector.Alt alt = evaded.get(0);
            Punishment ban = alt.globalBan().orElseThrow();
            logger.warn("{} ({}) joined from an address of {}, who is banned by #{}: possible ban evasion.",
                    player.getUsername(), player.getUniqueId(), alt.name(), ban.id());
            staff.announce(MessageKey.PUNISH_STAFF_EVASION,
                    Placeholder.unparsed("player", player.getUsername()),
                    Placeholder.unparsed("alt", alt.name()),
                    Placeholder.unparsed("id", Long.toString(ban.id())));
            if (settings.evasion() == AltsConfig.EvasionAction.BAN) {
                banEvader(player, alt, ban);
            }
        }

        if (settings.joinAlerts()) {
            int warns = service.activeWarns(player.getUniqueId()).size();
            long bannedAlts = found.stream().filter(AltDetector.Alt::banned).count();
            if (warns >= settings.joinAlertMinWarns() || bannedAlts > 0) {
                staff.announce(MessageKey.PUNISH_STAFF_JOIN_ALERT,
                        Placeholder.unparsed("player", player.getUsername()),
                        Placeholder.unparsed("warns", Integer.toString(warns)),
                        Placeholder.unparsed("alts", Long.toString(bannedAlts)));
            }
        }
    }

    /** Bans the evading account for as long as the evaded ban has left, on its own account only. */
    private void banEvader(Player player, AltDetector.Alt alt, Punishment evaded) {
        Instant now = service.clock().instant();
        if (!evaded.isActiveAt(now)) {
            return; // ran out in the meantime
        }
        PunishmentLength length = evaded.isPermanent() ? PunishmentLength.PERMANENT
                : PunishmentLength.of(Duration.between(now, evaded.expiresAt()));
        IssueResult result = service.issue(new PunishmentRequest(PunishmentType.BAN,
                new Target(player.getUniqueId(), player.getUsername(), null), IpMode.ACCOUNT,
                PunishmentScope.global(), length, "Ban evasion (" + alt.name() + ", #" + evaded.id() + ")", null,
                new IssuerRef(null, ISSUER_NAME, IssuerRef.Source.EVASION), false, false, evaded.id()));
        if (!(result instanceof IssueResult.Issued)) {
            logger.info("Did not ban {} for evasion: {}.", player.getUsername(), result);
        }
    }
}
