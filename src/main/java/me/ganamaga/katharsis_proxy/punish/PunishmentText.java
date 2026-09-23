package me.ganamaga.katharsis_proxy.punish;

import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentScope;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * Renders punishments for players and staff, in the reader's language.
 */
public final class PunishmentText {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Messages messages;
    private final Supplier<String> appealUrl;

    public PunishmentText(Messages messages, Supplier<String> appealUrl) {
        this.messages = messages;
        this.appealUrl = appealUrl;
    }

    public Messages messages() {
        return messages;
    }

    public Component type(String language, PunishmentType type) {
        return messages.render(language, switch (type) {
            case BAN -> MessageKey.PUNISH_TYPE_BAN;
            case MUTE -> MessageKey.PUNISH_TYPE_MUTE;
            case WARN -> MessageKey.PUNISH_TYPE_WARN;
            case KICK -> MessageKey.PUNISH_TYPE_KICK;
        });
    }

    public Component scope(String language, PunishmentScope scope) {
        return switch (scope.kind()) {
            case GLOBAL -> messages.render(language, MessageKey.PUNISH_SCOPE_GLOBAL);
            case SERVER -> messages.render(language, MessageKey.PUNISH_SCOPE_SERVER,
                    Placeholder.unparsed("name", scope.name()));
            case GROUP -> messages.render(language, MessageKey.PUNISH_SCOPE_GROUP,
                    Placeholder.unparsed("name", scope.name()));
        };
    }

    /** How long it was issued for. */
    public Component length(String language, Punishment punishment) {
        return messages.longDuration(language, punishment.isPermanent() ? null
                : Duration.between(punishment.createdAt(), punishment.expiresAt()));
    }

    /** How long it has left at {@code now}. */
    public Component remaining(String language, Punishment punishment, Instant now) {
        return messages.longDuration(language, punishment.isPermanent() ? null
                : Duration.between(now, punishment.expiresAt()));
    }

    public static String date(Instant instant) {
        return DATE.format(instant);
    }

    /** What a player sees when a network-wide ban turns them away. */
    public Component banScreen(String language, Punishment ban, Instant now) {
        Component appeal = appeal(language);
        return messages.render(language, MessageKey.PUNISH_BAN_SCREEN,
                Placeholder.unparsed("reason", ban.reason()),
                Placeholder.component("expires", remaining(language, ban, now)),
                Placeholder.unparsed("issuer", ban.issuer().name()),
                Placeholder.unparsed("id", Long.toString(ban.id())),
                Placeholder.component("appeal", appeal));
    }

    /** What a player sees when bans leave them no server to join. */
    public Component serverBanScreen(String language, Punishment ban, String server, Instant now) {
        return messages.render(language, MessageKey.PUNISH_SERVER_BAN_SCREEN,
                Placeholder.unparsed("server", server),
                Placeholder.unparsed("reason", ban.reason()),
                Placeholder.component("expires", remaining(language, ban, now)),
                Placeholder.unparsed("id", Long.toString(ban.id())),
                Placeholder.component("appeal", appeal(language)));
    }

    /** Told in chat to a player kept off {@code server}. */
    public Component bannedFromServer(String language, Punishment ban, String server, Instant now) {
        return messages.prefixed(language, MessageKey.PUNISH_BANNED_FROM_SERVER,
                Placeholder.unparsed("server", server),
                Placeholder.unparsed("reason", ban.reason()),
                Placeholder.component("remaining", remaining(language, ban, now)),
                Placeholder.unparsed("id", Long.toString(ban.id())));
    }

    /** Told in chat to a player sent to {@code server} instead. */
    public Component movedTo(String language, String server) {
        return messages.prefixed(language, MessageKey.PUNISH_MOVED_TO, Placeholder.unparsed("server", server));
    }

    private Component appeal(String language) {
        String url = appealUrl.get();
        return url.isBlank() ? Component.empty()
                : messages.render(language, MessageKey.PUNISH_APPEAL_LINE, Placeholder.unparsed("url", url));
    }

    /**
     * What to tell whoever asked for {@code result}, about {@code target},
     * without the prefix.
     */
    public Component issueResult(String language, IssueResult result, String target, PunishmentType type) {
        return switch (result) {
            case IssueResult.Issued issued -> messages.render(language,
                    type == PunishmentType.KICK ? MessageKey.PUNISH_KICKED : MessageKey.PUNISH_ISSUED,
                    Placeholder.unparsed("player", target),
                    Placeholder.component("type", type(language, type)),
                    Placeholder.component("scope", scope(language, issued.punishment().scope())),
                    Placeholder.component("duration", length(language, issued.punishment())),
                    Placeholder.unparsed("id", Long.toString(issued.punishment().id())));
            case IssueResult.AlreadyActive already -> messages.render(language, MessageKey.PUNISH_ALREADY_ACTIVE,
                    Placeholder.unparsed("player", target),
                    Placeholder.component("type", type(language, type)),
                    Placeholder.unparsed("id", Long.toString(already.existing().id())));
            case IssueResult.Exempt ignored ->
                    messages.render(language, MessageKey.PUNISH_TARGET_EXEMPT, Placeholder.unparsed("player", target));
            case IssueResult.Outranked ignored ->
                    messages.render(language, MessageKey.PUNISH_TARGET_OUTRANKS, Placeholder.unparsed("player", target));
            case IssueResult.NoAddress ignored ->
                    messages.render(language, MessageKey.PUNISH_NO_ADDRESS, Placeholder.unparsed("player", target));
            case IssueResult.Unavailable ignored -> messages.render(language, MessageKey.PUNISH_UNAVAILABLE);
        };
    }

    /** One line of {@code /history}. */
    public Component historyLine(String language, Punishment entry, Instant now) {
        Component status;
        if (entry.isRevoked()) {
            status = messages.render(language, MessageKey.PUNISH_STATUS_REVOKED,
                    Placeholder.unparsed("issuer", entry.revocation().by().name()));
        } else if (!entry.type().isLasting()) {
            status = messages.render(language, MessageKey.PUNISH_STATUS_DONE);
        } else {
            status = messages.render(language,
                    entry.isActiveAt(now) ? MessageKey.PUNISH_STATUS_ACTIVE : MessageKey.PUNISH_STATUS_EXPIRED);
        }
        return messages.render(language, MessageKey.PUNISH_HISTORY_LINE,
                Placeholder.unparsed("id", Long.toString(entry.id())),
                Placeholder.unparsed("date", date(entry.createdAt())),
                Placeholder.component("type", type(language, entry.type())),
                Placeholder.component("scope", scope(language, entry.scope())),
                Placeholder.component("duration", length(language, entry)),
                Placeholder.unparsed("reason", entry.reason()),
                Placeholder.unparsed("issuer", entry.issuer().name()),
                Placeholder.component("status", status));
    }

    /** One line of {@code /check}: a punishment in force. */
    public Component activeLine(String language, Punishment punishment, Instant now) {
        return messages.render(language, MessageKey.PUNISH_CHECK_ACTIVE,
                Placeholder.component("type", type(language, punishment.type())),
                Placeholder.component("scope", scope(language, punishment.scope())),
                Placeholder.component("remaining", remaining(language, punishment, now)),
                Placeholder.unparsed("reason", punishment.reason()),
                Placeholder.unparsed("id", Long.toString(punishment.id())));
    }

    public Component kickScreen(String language, Punishment kick) {
        return kickScreen(language, kick.reason(), kick.issuer().name());
    }

    public Component kickScreen(String language, String reason, String issuer) {
        return messages.render(language, MessageKey.PUNISH_KICK_SCREEN,
                Placeholder.unparsed("reason", reason),
                Placeholder.unparsed("issuer", issuer));
    }
}
