package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.Target;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code /history PLAYER|IP [page]}: everything ever issued against a player
 * or address, newest first, lifted and expired punishments included.
 */
public final class HistoryCommand extends StaffCommand {

    static final int PAGE_SIZE = 8;

    public HistoryCommand(PunishContext context) {
        super(context, "history", "/history <player|ip> [page]");
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
        int page;
        try {
            page = args.words().isEmpty() ? 1 : Math.max(1, Integer.parseInt(args.words().get(0)));
        } catch (NumberFormatException e) {
            sendUsage(source);
            return;
        }
        context.async(() -> resolve(source, args).ifPresent(target -> show(source, target, page)));
    }

    private void show(CommandSource source, Target target, int page) {
        List<Punishment> entries;
        int total;
        try {
            total = context.service().countHistory(target);
            entries = context.service().history(target, PAGE_SIZE, (page - 1) * PAGE_SIZE);
        } catch (SQLException e) {
            context.logger().error("Could not read the history of {}.", target.name(), e);
            send(source, MessageKey.PUNISH_UNAVAILABLE);
            return;
        }
        if (total == 0) {
            send(source, MessageKey.PUNISH_HISTORY_EMPTY, Placeholder.unparsed("player", target.name()));
            return;
        }
        String language = language(source);
        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        send(source, MessageKey.PUNISH_HISTORY_HEADER, Placeholder.unparsed("player", target.name()),
                Placeholder.unparsed("page", Integer.toString(Math.min(page, pages))),
                Placeholder.unparsed("pages", Integer.toString(pages)));
        Instant now = context.service().clock().instant();
        for (Punishment entry : entries) {
            source.sendMessage(line(language, entry, now));
        }
    }

    private Component line(String language, Punishment entry, Instant now) {
        PunishmentText text = context.text();
        return context.messages().render(language, MessageKey.PUNISH_HISTORY_LINE,
                Placeholder.unparsed("id", Long.toString(entry.id())),
                Placeholder.unparsed("date", PunishmentText.date(entry.createdAt())),
                Placeholder.component("type", text.type(language, entry.type())),
                Placeholder.component("scope", text.scope(language, entry.scope())),
                Placeholder.component("duration", text.length(language, entry)),
                Placeholder.unparsed("reason", entry.reason()),
                Placeholder.unparsed("issuer", entry.issuer().name()),
                Placeholder.component("status", status(language, entry, now)));
    }

    private Component status(String language, Punishment entry, Instant now) {
        if (entry.isRevoked()) {
            return context.messages().render(language, MessageKey.PUNISH_STATUS_REVOKED,
                    Placeholder.unparsed("issuer", entry.revocation().by().name()));
        }
        if (!entry.type().isLasting()) {
            return context.messages().render(language, MessageKey.PUNISH_STATUS_DONE);
        }
        return context.messages().render(language,
                entry.isActiveAt(now) ? MessageKey.PUNISH_STATUS_ACTIVE : MessageKey.PUNISH_STATUS_EXPIRED);
    }
}
