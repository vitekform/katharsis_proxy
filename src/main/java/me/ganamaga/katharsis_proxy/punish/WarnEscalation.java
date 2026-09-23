package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.config.EscalationStepConfig;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Gives a stronger punishment automatically when a player's active warnings
 * reach a configured count.
 *
 * <p>A step fires when the count lands exactly on it, so the warnings after it
 * do not give the same punishment again; the next step takes over. Warnings
 * that expire or are lifted stop counting. What is given holds the account
 * only, never the address: nobody checked who else uses it.</p>
 */
public final class WarnEscalation implements PunishmentObserver {

    public static final String ISSUER_NAME = "Escalation";

    /**
     * One step.
     *
     * @param count    active warnings that trigger it
     * @param template the template to give, or {@code null}
     * @param type     without a template, what to give
     * @param length   without a template, how long
     * @param reason   without a template, why
     * @param scope    where, as typed after {@code -s}; {@code null} for the warning's own scope
     */
    public record Step(int count, String template, PunishmentType type, PunishmentLength length, String reason,
                       String scope) {
    }

    /** What a step gives a particular player. */
    public record Outcome(PunishmentType type, PunishmentLength length, String reason, String template,
                          String scope) {
    }

    private final Logger logger;
    private final PunishmentService service;
    private final TemplateRegistry templates;
    private final List<Step> steps;
    private final ScopeResolver scopes;
    private final Supplier<Collection<String>> servers;

    public WarnEscalation(Logger logger, PunishmentService service, TemplateRegistry templates, List<Step> steps,
                          ScopeResolver scopes, Supplier<Collection<String>> servers) {
        this.logger = logger;
        this.service = service;
        this.templates = templates;
        this.steps = steps.stream().sorted(Comparator.comparingInt(Step::count)).toList();
        this.scopes = scopes;
        this.servers = servers;
    }

    /** Reads the configured steps, skipping and reporting to {@code warn} any that cannot be used. */
    public static List<Step> load(List<EscalationStepConfig> configured, TemplateRegistry templates,
                                  Consumer<String> warn) {
        List<Step> loaded = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            EscalationStepConfig config = configured.get(i);
            String where = "punishments.warns.escalation[" + i + "]";
            if (config.count() < 1) {
                warn.accept(where + ".count must be at least 1; the step is skipped.");
                continue;
            }
            String scope = config.scope().isBlank() ? null : config.scope();
            if (!config.template().isBlank()) {
                Optional<TemplateRegistry.Template> template = templates.find(config.template());
                if (template.isEmpty() || template.get().type() == PunishmentType.WARN) {
                    warn.accept(where + ".template '" + config.template()
                            + "' is not a ban or mute template; the step is skipped.");
                    continue;
                }
                loaded.add(new Step(config.count(), template.get().name(), null, null, null, scope));
                continue;
            }
            Optional<PunishmentType> type = TemplateRegistry.parseType(config.type());
            Optional<PunishmentLength> length = DurationParser.parse(config.duration());
            if (type.isEmpty() || type.get() == PunishmentType.WARN) {
                warn.accept(where + ".type must be ban, mute or kick; the step is skipped.");
                continue;
            }
            if (length.isEmpty()) {
                warn.accept(where + ".duration '" + config.duration() + "' is not a length; the step is skipped.");
                continue;
            }
            String reason = config.reason().isBlank() ? "Reached " + config.count() + " warnings" : config.reason();
            loaded.add(new Step(config.count(), null, type.get(), length.get(), reason, scope));
        }
        return loaded;
    }

    public List<Step> steps() {
        return steps;
    }

    /** The first step above {@code active} warnings, if any. */
    public Optional<Step> next(int active) {
        return steps.stream().filter(step -> step.count() > active).findFirst();
    }

    /** What {@code step} gives the account {@code uuid}, its template's ladder applied. */
    public Optional<Outcome> outcome(Step step, UUID uuid) throws SQLException {
        if (step.template() == null) {
            return Optional.of(new Outcome(step.type(), step.length(), step.reason(), null, step.scope()));
        }
        Optional<TemplateRegistry.Template> found = templates.find(step.template());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TemplateRegistry.Template template = found.get();
        return Optional.of(new Outcome(template.type(),
                template.lengthFor(service.templateCount(uuid, template.name())), template.reason(),
                template.name(), step.scope() != null ? step.scope() : template.scope()));
    }

    @Override
    public void issued(Punishment warning) {
        if (warning.type() != PunishmentType.WARN || warning.targetUuid() == null || steps.isEmpty()) {
            return;
        }
        UUID uuid = warning.targetUuid();
        try {
            int active = service.activeWarns(uuid).size();
            Optional<Step> step = steps.stream().filter(s -> s.count() == active).findFirst();
            if (step.isEmpty()) {
                return;
            }
            Optional<Outcome> outcome = outcome(step.get(), uuid);
            if (outcome.isEmpty()) {
                return;
            }
            PunishmentScope scope = outcome.get().scope() == null ? warning.scope()
                    : scopes.parse(outcome.get().scope(), servers.get()).orElse(warning.scope());
            IssueResult result = service.issue(new PunishmentRequest(outcome.get().type(),
                    new Target(uuid, warning.targetName(), null), IpMode.ACCOUNT, scope, outcome.get().length(),
                    outcome.get().reason(), outcome.get().template(),
                    new IssuerRef(null, ISSUER_NAME, IssuerRef.Source.ESCALATION), false, false, warning.id()));
            if (!(result instanceof IssueResult.Issued)) {
                logger.info("{} reached {} warnings, but the escalation was not issued: {}.",
                        warning.targetName(), active, result);
            }
        } catch (SQLException e) {
            logger.error("Could not escalate the warnings of {}.", warning.targetName(), e);
        }
    }
}
