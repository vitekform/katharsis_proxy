package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.config.AutoModConfig;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.entity.ChatMessage;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.moderation.ModerationAlerts;
import me.ganamaga.katharsis_proxy.moderation.ModerationVerdict;
import me.ganamaga.katharsis_proxy.util.CooldownManager;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Warns players whose chat the moderation filters held back, so repeated
 * offences escalate like warnings from staff.
 *
 * <p>A message that could not be checked at all is not held against anyone.
 * Each player gets at most one automatic warning per cooldown, so a burst of
 * flagged messages counts once. The warning applies on the server the message
 * was written on.</p>
 */
public final class AutoWarn implements ModerationAlerts {

    private final Logger logger;
    private final PunishmentService service;
    private final TemplateRegistry templates;
    private final Supplier<PunishmentsConfig> config;
    private final Consumer<Runnable> async;
    private final Supplier<Collection<String>> servers;
    private final CooldownManager cooldowns;

    /**
     * @param async   runs the warning off the chat handling, which must not block
     * @param servers the {@code velocity.toml} server names
     */
    public AutoWarn(Logger logger, PunishmentService service, TemplateRegistry templates,
                    Supplier<PunishmentsConfig> config, Consumer<Runnable> async, Supplier<Collection<String>> servers) {
        this.logger = logger;
        this.service = service;
        this.templates = templates;
        this.config = config;
        this.async = async;
        this.servers = servers;
        String cooldown = config.get().automod().cooldown();
        this.cooldowns = new CooldownManager(DurationParser.parse(cooldown)
                .map(PunishmentLength::duration)
                .orElse(Duration.ZERO));
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    @Override
    public void flagged(ChatMessage message, ModerationVerdict verdict) {
        AutoModConfig automod = config.get().automod();
        if (!automod.enabled() || !verdict.isFlagged() || !verdict.source().isViolation()) {
            return;
        }
        if (!cooldowns.remaining(message.playerUuid()).isZero()) {
            return;
        }
        cooldowns.start(message.playerUuid());
        async.accept(() -> warn(message, verdict, automod));
    }

    void warn(ChatMessage message, ModerationVerdict verdict, AutoModConfig automod) {
        String issuerName = automod.issuerName();
        IssuerRef issuer = new IssuerRef(null,
                issuerName.length() > IssuerRef.NAME_MAX_LENGTH
                        ? issuerName.substring(0, IssuerRef.NAME_MAX_LENGTH) : issuerName,
                IssuerRef.Source.AUTOMOD);
        String because = "Chat: " + verdict.reason();
        PunishmentScope scope = servers.get().stream()
                .filter(server -> server.equalsIgnoreCase(message.server()))
                .findFirst()
                .map(PunishmentScope::server)
                .orElse(PunishmentScope.global());
        Target target = new Target(message.playerUuid(), message.playerName(), null);

        PunishmentType type = PunishmentType.WARN;
        PunishmentLength length = DurationParser.parse(config.get().warns().expireAfter())
                .orElse(PunishmentLength.PERMANENT);
        String reason = because;
        String template = null;
        try {
            Optional<TemplateRegistry.Template> configured = automod.template().isBlank()
                    ? Optional.empty() : templates.find(automod.template());
            if (configured.isPresent()) {
                TemplateRegistry.Template found = configured.get();
                type = found.type();
                template = found.name();
                reason = found.reason() + " (" + because + ")";
                if (type != PunishmentType.WARN) {
                    length = found.lengthFor(service.templateCount(target.uuid(), found.name()));
                }
            }
        } catch (SQLException e) {
            logger.error("Could not look up the offences of {}; giving a plain warning.", message.playerName(), e);
            type = PunishmentType.WARN;
            reason = because;
            template = null;
        }
        IssueResult result = service.issue(new PunishmentRequest(type, target, IpMode.ACCOUNT, scope, length,
                reason, template, issuer, false, false, null));
        if (!(result instanceof IssueResult.Issued)) {
            logger.info("Did not punish {} for flagged chat: {}.", message.playerName(), result);
        }
    }
}
