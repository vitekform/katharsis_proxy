package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Optional;

/**
 * Issues a lasting punishment: {@code /ban}, {@code /tempban}, {@code /banip}
 * and their mute counterparts.
 *
 * <pre>
 * /ban     PLAYER [flags] [reason...]
 * /tempban PLAYER DURATION [flags] [reason...]
 * /banip   PLAYER|IP [DURATION] [flags] [reason...]
 * </pre>
 */
public final class IssueCommand extends StaffCommand {

    /** How the command reads its length and whom it holds. */
    public enum Variant {
        /** Permanent. */
        PERMANENT,
        /** A duration must follow the target. */
        TEMPORARY,
        /** The address only; a duration may follow the target. */
        ADDRESS
    }

    private final PunishmentType type;
    private final Variant variant;

    public IssueCommand(PunishContext context, String name, PunishmentType type, Variant variant) {
        super(context, name, "/" + name + switch (variant) {
            case PERMANENT -> " <player> [-g|-s server] [-a|-i] [-q] [-f] [reason]";
            case TEMPORARY -> " <player> <duration> [-g|-s server] [-a|-i] [-q] [-f] [reason]";
            case ADDRESS -> " <player|ip> [duration] [-g|-s server] [-q] [-f] [reason]";
        });
        this.type = type;
        this.variant = variant;
    }

    @Override
    protected boolean takesDuration() {
        return variant != Variant.PERMANENT;
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

        int reasonFrom = 0;
        PunishmentLength length = PunishmentLength.PERMANENT;
        if (variant != Variant.PERMANENT && !args.words().isEmpty()) {
            Optional<PunishmentLength> given = DurationParser.parse(args.words().get(0));
            if (given.isPresent()) {
                length = given.get();
                reasonFrom = 1;
            } else if (variant == Variant.TEMPORARY) {
                send(source, MessageKey.PUNISH_INVALID_DURATION,
                        Placeholder.unparsed("input", args.words().get(0)));
                return;
            }
        } else if (variant == Variant.TEMPORARY) {
            sendUsage(source);
            return;
        }

        Optional<PunishmentScope> scope = issueScope(source, args);
        if (scope.isEmpty()) {
            return;
        }
        String reason = Optional.ofNullable(args.rest(reasonFrom)).orElseGet(() -> context.config().get().defaultReason());
        PunishmentLength finalLength = length;
        context.async(() -> resolve(source, args).ifPresent(target ->
                issue(source, args, target, scope.get(), finalLength, reason)));
    }

    private void issue(CommandSource source, PunishArgs args, Target target, PunishmentScope scope,
                       PunishmentLength length, String reason) {
        PunishmentRequest request = new PunishmentRequest(type, target, ipMode(args, target), scope, length,
                reason, null, PunishContext.issuer(source), args.silent(), args.force(), null);
        IssueResult result = context.service().issue(request);
        String language = language(source);
        switch (result) {
            case IssueResult.Issued issued -> {
                Punishment punishment = issued.punishment();
                send(source, MessageKey.PUNISH_ISSUED,
                        Placeholder.unparsed("player", target.name()),
                        Placeholder.component("type", context.text().type(language, type)),
                        Placeholder.component("scope", context.text().scope(language, punishment.scope())),
                        Placeholder.component("duration", context.text().length(language, punishment)),
                        Placeholder.unparsed("id", Long.toString(punishment.id())));
                if (issued.addressSkipped()) {
                    send(source, MessageKey.PUNISH_ADDRESS_SKIPPED, Placeholder.unparsed("player", target.name()));
                }
            }
            case IssueResult.AlreadyActive already -> send(source, MessageKey.PUNISH_ALREADY_ACTIVE,
                    Placeholder.unparsed("player", target.name()),
                    Placeholder.component("type", context.text().type(language, type)),
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

    private IpMode ipMode(PunishArgs args, Target target) {
        if (variant == Variant.ADDRESS || args.ipOnly() || target.isAddress()) {
            return IpMode.IP_ONLY;
        }
        if (args.accountOnly()) {
            return IpMode.ACCOUNT;
        }
        return type == PunishmentType.BAN && context.config().get().banIpByDefault()
                ? IpMode.ACCOUNT_AND_IP : IpMode.ACCOUNT;
    }
}
