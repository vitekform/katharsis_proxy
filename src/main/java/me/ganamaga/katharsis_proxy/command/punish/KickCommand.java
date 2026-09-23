package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.IpAddresses;
import me.ganamaga.katharsis_proxy.punish.IpMode;
import me.ganamaga.katharsis_proxy.punish.IssueResult;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.PunishmentLength;
import me.ganamaga.katharsis_proxy.punish.PunishmentRequest;
import me.ganamaga.katharsis_proxy.punish.Target;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Optional;

/**
 * {@code /kick PLAYER [-q] [reason...]}: disconnects a player online, and
 * keeps the kick in their history.
 */
public final class KickCommand extends StaffCommand {

    public KickCommand(PunishContext context) {
        super(context, "kick", "/kick <player> [-q] [reason]");
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
        Optional<Player> online = context.proxy().getPlayer(args.target());
        if (online.isEmpty()) {
            send(source, MessageKey.PUNISH_NOT_ONLINE, Placeholder.unparsed("player", args.target()));
            return;
        }
        Player player = online.get();
        Target target = new Target(player.getUniqueId(), player.getUsername(),
                IpAddresses.of(player.getRemoteAddress()).orElse(null));
        String reason = Optional.ofNullable(args.rest(0)).orElseGet(() -> context.config().get().defaultReason());
        PunishmentRequest request = new PunishmentRequest(PunishmentType.KICK, target, IpMode.ACCOUNT,
                PunishmentScope.global(), PunishmentLength.PERMANENT, reason, null, PunishContext.issuer(source),
                args.silent(), false, null);
        context.async(() -> {
            switch (context.service().issue(request)) {
                case IssueResult.Issued issued -> send(source, MessageKey.PUNISH_KICKED,
                        Placeholder.unparsed("player", target.name()),
                        Placeholder.unparsed("id", Long.toString(issued.punishment().id())));
                case IssueResult.Exempt ignored ->
                        send(source, MessageKey.PUNISH_TARGET_EXEMPT, Placeholder.unparsed("player", target.name()));
                case IssueResult.Outranked ignored ->
                        send(source, MessageKey.PUNISH_TARGET_OUTRANKS, Placeholder.unparsed("player", target.name()));
                case IssueResult.AlreadyActive ignored -> {
                    // Kicks never last, so none is ever already active.
                }
                case IssueResult.NoAddress ignored -> {
                    // A kick holds the account, never the address.
                }
                case IssueResult.Unavailable ignored -> {
                    // The kick could not be recorded; it still happens.
                    player.disconnect(context.text().kickScreen(context.messages().languageOf(player), reason,
                            request.issuer().name()));
                    send(source, MessageKey.PUNISH_UNAVAILABLE);
                }
            }
        });
    }
}
