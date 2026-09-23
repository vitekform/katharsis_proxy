package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.DurationParser;
import me.ganamaga.katharsis_proxy.punish.IpMode;
import me.ganamaga.katharsis_proxy.punish.IssueResult;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.PunishmentLength;
import me.ganamaga.katharsis_proxy.punish.PunishmentRequest;
import me.ganamaga.katharsis_proxy.punish.Target;
import me.ganamaga.katharsis_proxy.punish.TemplateRegistry;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Issues a lasting punishment: {@code /ban}, {@code /tempban}, {@code /banip},
 * their mute counterparts {@code /mute}, {@code /tempmute}, {@code /muteip},
 * {@code /warn}, and {@code /punish}, which gives whatever a template says.
 *
 * <pre>
 * /ban     PLAYER [flags] [#template] [reason...]
 * /tempban PLAYER DURATION [flags] [reason...]
 * /banip   PLAYER|IP [DURATION] [flags] [reason...]
 * /warn    PLAYER [flags] [#template] [reason...]
 * /punish  PLAYER #template [flags] [details...]
 * </pre>
 *
 * <p>A template supplies the reason, its scope unless the flags name one, and
 * a length from its ladder: the more of the same template the player has had
 * before, the longer. Anything typed after it is added to the reason.</p>
 */
public final class IssueCommand extends StaffCommand {

    /** How the command reads its length and whom it holds. */
    public enum Variant {
        /** Permanent, or as a template says; a warning lasts as configured. */
        PERMANENT,
        /** A duration must follow the target, unless a template is given. */
        TEMPORARY,
        /** The address only; a duration may follow the target. */
        ADDRESS
    }

    /** What the command gives; {@code null} for {@code /punish}, where the template decides. */
    private final PunishmentType type;
    private final Variant variant;

    public IssueCommand(PunishContext context, String name, PunishmentType type, Variant variant) {
        super(context, name, usage(name, type, variant));
        this.type = type;
        this.variant = variant;
    }

    private static String usage(String name, PunishmentType type, Variant variant) {
        if (type == null) {
            return "/" + name + " <player> <#template> [-g|-s server] [-a|-i] [-q] [-f] [details]";
        }
        if (type == PunishmentType.WARN) {
            return "/" + name + " <player> [-g|-s server] [-q] [#template] [reason]";
        }
        return "/" + name + switch (variant) {
            case PERMANENT -> " <player> [-g|-s server] [-a|-i] [-q] [-f] [#template] [reason]";
            case TEMPORARY -> " <player> <duration> [-g|-s server] [-a|-i] [-q] [-f] [reason]";
            case ADDRESS -> " <player|ip> [duration] [-g|-s server] [-q] [-f] [reason]";
        };
    }

    @Override
    protected boolean takesDuration() {
        return variant != Variant.PERMANENT;
    }

    @Override
    protected boolean takesTemplate() {
        return true;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        Optional<PunishArgs> parsed = PunishArgs.parse(invocation.arguments());
        if (parsed.isEmpty()) {
            sendUsage(source);
            return;
        }
        PunishArgs args = parsed.get();
        List<String> words = args.words();
        int next = 0;

        PunishmentLength explicit = null;
        if (variant != Variant.PERMANENT && next < words.size()) {
            Optional<PunishmentLength> given = DurationParser.parse(words.get(next));
            if (given.isPresent()) {
                explicit = given.get();
                next++;
            }
        }

        TemplateRegistry.Template template = null;
        if (next < words.size() && words.get(next).startsWith("#")) {
            String name = words.get(next).substring(1);
            Optional<TemplateRegistry.Template> found = context.templates().find(name);
            if (found.isEmpty()) {
                send(source, MessageKey.PUNISH_UNKNOWN_TEMPLATE, Placeholder.unparsed("name", name));
                return;
            }
            template = found.get();
            next++;
            if (type != null && template.type() != type) {
                send(source, MessageKey.PUNISH_TEMPLATE_TYPE, Placeholder.unparsed("name", template.name()),
                        Placeholder.component("type", context.text().type(language(source), template.type())));
                return;
            }
        }
        if (type == null && template == null) {
            sendUsage(source);
            return;
        }
        if (variant == Variant.TEMPORARY && explicit == null && template == null) {
            if (words.isEmpty()) {
                sendUsage(source);
            } else {
                send(source, MessageKey.PUNISH_INVALID_DURATION, Placeholder.unparsed("input", words.get(0)));
            }
            return;
        }

        Optional<PunishmentScope> scope = issueScope(source, args, template == null ? null : template.scope());
        if (scope.isEmpty()) {
            return;
        }
        String extra = args.rest(next);
        String reason = template == null
                ? Optional.ofNullable(extra).orElseGet(() -> context.config().get().defaultReason())
                : extra == null ? template.reason() : template.reason() + " - " + extra;
        TemplateRegistry.Template chosen = template;
        PunishmentLength length = explicit;
        context.async(() -> resolve(source, args).ifPresent(target ->
                issue(source, args, target, scope.get(), chosen, length, reason)));
    }

    private void issue(CommandSource source, PunishArgs args, Target target, PunishmentScope scope,
                       TemplateRegistry.Template template, PunishmentLength explicit, String reason) {
        PunishmentType given = template == null ? type : template.type();
        if (given == PunishmentType.WARN && target.isAddress()) {
            // A warning is about what an account did.
            send(source, MessageKey.PUNISH_UNKNOWN_PLAYER, Placeholder.unparsed("player", target.name()));
            return;
        }
        PunishmentLength length;
        try {
            length = length(given, template, explicit, target);
        } catch (SQLException e) {
            context.logger().error("Could not count the earlier offences of {}.", target.name(), e);
            send(source, MessageKey.PUNISH_UNAVAILABLE);
            return;
        }
        PunishmentRequest request = new PunishmentRequest(given, target, ipMode(given, args, target), scope, length,
                reason, template == null ? null : template.name(), PunishContext.issuer(source), args.silent(),
                args.force(), null);
        IssueResult result = context.service().issue(request);
        String language = language(source);
        switch (result) {
            case IssueResult.Issued issued -> {
                Punishment punishment = issued.punishment();
                send(source, MessageKey.PUNISH_ISSUED,
                        Placeholder.unparsed("player", target.name()),
                        Placeholder.component("type", context.text().type(language, given)),
                        Placeholder.component("scope", context.text().scope(language, punishment.scope())),
                        Placeholder.component("duration", context.text().length(language, punishment)),
                        Placeholder.unparsed("id", Long.toString(punishment.id())));
                if (issued.addressSkipped()) {
                    send(source, MessageKey.PUNISH_ADDRESS_SKIPPED, Placeholder.unparsed("player", target.name()));
                }
                if (given == PunishmentType.MUTE) {
                    warnWithoutCompanion(source, target);
                }
            }
            case IssueResult.AlreadyActive already -> send(source, MessageKey.PUNISH_ALREADY_ACTIVE,
                    Placeholder.unparsed("player", target.name()),
                    Placeholder.component("type", context.text().type(language, given)),
                    Placeholder.unparsed("id", Long.toString(already.existing().id())));
            case IssueResult.Exempt ignored ->
                    send(source, MessageKey.PUNISH_TARGET_EXEMPT, Placeholder.unparsed("player", target.name()));
            case IssueResult.Outranked ignored ->
                    send(source, MessageKey.PUNISH_TARGET_OUTRANKS, Placeholder.unparsed("player", target.name()));
            case IssueResult.NoAddress ignored ->
                    send(source, MessageKey.PUNISH_NO_ADDRESS, Placeholder.unparsed("player", target.name()));
            case IssueResult.Unavailable ignored -> send(source, MessageKey.PUNISH_UNAVAILABLE);
        }
    }

    /**
     * A length typed out wins; then a template's ladder; a warning otherwise
     * lasts as configured, and anything else is permanent.
     */
    private PunishmentLength length(PunishmentType given, TemplateRegistry.Template template,
                                    PunishmentLength explicit, Target target) throws SQLException {
        if (explicit != null) {
            return explicit;
        }
        if (template != null && (given != PunishmentType.WARN || !template.ladder().isEmpty())) {
            return template.lengthFor(context.service().templateCount(target.uuid(), template.name()));
        }
        if (given == PunishmentType.WARN) {
            return DurationParser.parse(context.config().get().warns().expireAfter())
                    .orElse(PunishmentLength.PERMANENT);
        }
        return PunishmentLength.PERMANENT;
    }

    /**
     * Mutes are enforced by the companion plugin; tell the issuer if the
     * target is on a server without it, where they can still chat.
     */
    private void warnWithoutCompanion(CommandSource source, Target target) {
        if (target.isAddress()) {
            return;
        }
        context.proxy().getPlayer(target.uuid())
                .flatMap(Player::getCurrentServer)
                .map(server -> server.getServerInfo().getName())
                .filter(server -> !context.companions().servers().contains(server))
                .ifPresent(server -> send(source, MessageKey.PUNISH_NO_COMPANION,
                        Placeholder.unparsed("server", server)));
    }

    private IpMode ipMode(PunishmentType given, PunishArgs args, Target target) {
        if (variant == Variant.ADDRESS || args.ipOnly() || target.isAddress()) {
            return IpMode.IP_ONLY;
        }
        if (args.accountOnly()) {
            return IpMode.ACCOUNT;
        }
        boolean withAddress = switch (given) {
            case BAN -> context.config().get().banIpByDefault();
            case MUTE -> context.config().get().muteIpByDefault();
            default -> false;
        };
        return withAddress ? IpMode.ACCOUNT_AND_IP : IpMode.ACCOUNT;
    }
}
