package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.RevokeRequest;
import me.ganamaga.katharsis_proxy.punish.RevokeResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Optional;

/**
 * Lifts punishments: {@code /unban} and {@code /unmute}.
 *
 * <pre>
 * /unban PLAYER|IP [-g|-s SERVER] [reason...]   every active ban, or those on one scope
 * /unban #ID [reason...]                        one ban
 * </pre>
 *
 * <p>An account's bans are the ones naming the account; a ban naming only an
 * address it shares is lifted by naming the address.</p>
 */
public final class RevokeCommand extends StaffCommand {

    private final PunishmentType type;

    public RevokeCommand(PunishContext context, String name, PunishmentType type) {
        super(context, name, "/" + name + " <player|ip|#id> [-g|-s server] [reason]");
        this.type = type;
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
        String reason = args.rest(0);

        Optional<Long> id = parseId(args.target());
        if (id.isPresent()) {
            context.async(() -> report(source, "#" + id.get(), id.get(), context.service().revoke(
                    RevokeRequest.byId(type, id.get(), PunishContext.issuer(source), reason))));
            return;
        }

        Optional<PunishmentScope> scope = explicitScope(source, args);
        if (scope != null && scope.isEmpty()) {
            return; // unknown scope, already said
        }
        PunishmentScope only = scope == null ? null : scope.get();
        context.async(() -> resolve(source, args).ifPresent(target -> report(source, target.name(), null,
                context.service().revoke(RevokeRequest.byTarget(type, target, only,
                        PunishContext.issuer(source), reason)))));
    }

    private void report(CommandSource source, String targetName, Long id, RevokeResult result) {
        String language = language(source);
        switch (result) {
            case RevokeResult.Revoked revoked -> send(source, MessageKey.PUNISH_REVOKED,
                    Placeholder.unparsed("player", revoked.punishments().get(0).targetName()),
                    Placeholder.component("type", context.text().type(language, type)),
                    Placeholder.unparsed("count", Integer.toString(revoked.punishments().size())));
            case RevokeResult.NothingActive ignored -> {
                if (id != null) {
                    send(source, MessageKey.PUNISH_UNKNOWN_ID, Placeholder.unparsed("id", Long.toString(id)),
                            Placeholder.component("type", context.text().type(language, type)));
                } else {
                    send(source, MessageKey.PUNISH_NOTHING_ACTIVE, Placeholder.unparsed("player", targetName),
                            Placeholder.component("type", context.text().type(language, type)));
                }
            }
            case RevokeResult.Unavailable ignored -> send(source, MessageKey.PUNISH_UNAVAILABLE);
        }
    }

    /** {@code #123} as 123. */
    static Optional<Long> parseId(String input) {
        if (input.length() < 2 || input.charAt(0) != '#') {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(input.substring(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
