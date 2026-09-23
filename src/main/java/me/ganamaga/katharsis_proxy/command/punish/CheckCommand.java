package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import me.ganamaga.katharsis_proxy.entity.PlayerHistory;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.PunishPermissions;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.Target;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code /check PLAYER|IP}: what is in force against a player right now, and
 * when and from where they were last seen.
 */
public final class CheckCommand extends StaffCommand {

    public CheckCommand(PunishContext context) {
        super(context, "check", "/check <player|ip>");
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
        context.async(() -> resolve(source, args).ifPresent(target -> show(source, target)));
    }

    private void show(CommandSource source, Target target) {
        List<Punishment> active;
        Optional<PlayerHistory> seen;
        try {
            active = context.service().activeAgainst(target.uuid(), target.ip());
            seen = target.isAddress() ? Optional.empty() : context.history().find(target.uuid());
        } catch (SQLException e) {
            context.logger().error("Could not check {}.", target.name(), e);
            send(source, MessageKey.PUNISH_UNAVAILABLE);
            return;
        }
        String language = language(source);
        boolean seeIp = source.hasPermission(PunishPermissions.SEE_IP);
        send(source, MessageKey.PUNISH_CHECK_HEADER, Placeholder.unparsed("player", target.name()),
                Placeholder.unparsed("uuid", target.isAddress() ? "" : target.uuid().toString()));
        seen.ifPresent(history -> send(source, MessageKey.PUNISH_CHECK_SEEN,
                Placeholder.unparsed("first", PunishmentText.date(history.firstSeen())),
                Placeholder.unparsed("last", PunishmentText.date(history.lastSeen())),
                Placeholder.component("ip", ip(language, target.ip(), seeIp))));
        if (active.isEmpty()) {
            send(source, MessageKey.PUNISH_CHECK_NONE);
            return;
        }
        Instant now = context.service().clock().instant();
        for (Punishment punishment : active) {
            source.sendMessage(context.text().activeLine(language, punishment, now));
        }
    }

    private Component ip(String language, String ip, boolean seeIp) {
        if (ip != null && seeIp) {
            return Component.text(ip);
        }
        return context.messages().render(language, MessageKey.PUNISH_IP_HIDDEN);
    }
}
