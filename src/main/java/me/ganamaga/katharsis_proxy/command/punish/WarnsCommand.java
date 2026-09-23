package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.Target;
import me.ganamaga.katharsis_proxy.punish.WarnEscalation;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code /warns PLAYER}: a player's active warnings, and what the next one
 * adds up to.
 */
public final class WarnsCommand extends StaffCommand {

    public WarnsCommand(PunishContext context) {
        super(context, "warns", "/warns <player>");
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
        if (target.isAddress()) {
            send(source, MessageKey.PUNISH_UNKNOWN_PLAYER, Placeholder.unparsed("player", target.name()));
            return;
        }
        List<Punishment> warns;
        Optional<WarnEscalation.Outcome> next = Optional.empty();
        Optional<WarnEscalation.Step> step;
        try {
            warns = context.service().activeWarns(target.uuid());
            step = context.escalation().next(warns.size());
            if (step.isPresent()) {
                next = context.escalation().outcome(step.get(), target.uuid());
            }
        } catch (SQLException e) {
            context.logger().error("Could not read the warnings of {}.", target.name(), e);
            send(source, MessageKey.PUNISH_UNAVAILABLE);
            return;
        }
        String language = language(source);
        PunishmentText text = context.text();
        Instant now = context.service().clock().instant();
        send(source, MessageKey.PUNISH_WARNS_HEADER, Placeholder.unparsed("player", target.name()),
                Placeholder.unparsed("count", Integer.toString(warns.size())));
        for (Punishment warn : warns) {
            source.sendMessage(context.messages().render(language, MessageKey.PUNISH_WARNS_LINE,
                    Placeholder.unparsed("id", Long.toString(warn.id())),
                    Placeholder.unparsed("date", PunishmentText.date(warn.createdAt())),
                    Placeholder.unparsed("reason", warn.reason()),
                    Placeholder.unparsed("issuer", warn.issuer().name()),
                    Placeholder.component("remaining", text.remaining(language, warn, now))));
        }
        if (step.isPresent() && next.isPresent()) {
            WarnEscalation.Outcome outcome = next.get();
            source.sendMessage(context.messages().render(language, MessageKey.PUNISH_WARNS_NEXT,
                    Placeholder.unparsed("count", Integer.toString(step.get().count())),
                    Placeholder.component("type", text.type(language, outcome.type())),
                    Placeholder.component("duration", context.messages().longDuration(language,
                            outcome.length().duration()))));
        }
    }
}
