package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.punish.AltDetector;
import me.ganamaga.katharsis_proxy.punish.PunishArgs;
import me.ganamaga.katharsis_proxy.punish.PunishPermissions;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.Target;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * {@code /alts PLAYER}: the other accounts that have played from the player's
 * addresses recently, and whether they are banned or muted. The addresses
 * themselves are only shown to staff with {@link PunishPermissions#SEE_IP}.
 */
public final class AltsCommand extends StaffCommand {

    private final AltDetector detector;

    public AltsCommand(PunishContext context, AltDetector detector) {
        super(context, "alts", "/alts <player>");
        this.detector = detector;
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
        List<AltDetector.Alt> alts;
        try {
            alts = detector.alts(target.uuid());
        } catch (SQLException e) {
            context.logger().error("Could not look up the alts of {}.", target.name(), e);
            send(source, MessageKey.PUNISH_UNAVAILABLE);
            return;
        }
        if (alts.isEmpty()) {
            send(source, MessageKey.PUNISH_ALTS_NONE, Placeholder.unparsed("player", target.name()));
            return;
        }
        String language = language(source);
        boolean seeIp = source.hasPermission(PunishPermissions.SEE_IP);
        send(source, MessageKey.PUNISH_ALTS_HEADER, Placeholder.unparsed("player", target.name()),
                Placeholder.unparsed("count", Integer.toString(alts.size())));
        for (AltDetector.Alt alt : alts) {
            source.sendMessage(context.messages().render(language, MessageKey.PUNISH_ALTS_LINE,
                    Placeholder.unparsed("player", alt.name()),
                    Placeholder.component("status", status(language, alt)),
                    Placeholder.component("shared", shared(language, alt, seeIp)),
                    Placeholder.unparsed("last", PunishmentText.date(alt.lastSeen()))));
        }
    }

    private Component status(String language, AltDetector.Alt alt) {
        MessageKey key = alt.banned() ? MessageKey.PUNISH_ALT_BANNED
                : alt.muted() ? MessageKey.PUNISH_ALT_MUTED : MessageKey.PUNISH_ALT_CLEAN;
        return context.messages().render(language, key);
    }

    private Component shared(String language, AltDetector.Alt alt, boolean seeIp) {
        if (seeIp) {
            return Component.text(String.join(", ", alt.sharedIps()));
        }
        return context.messages().render(language, MessageKey.PUNISH_ALTS_SHARED,
                Placeholder.unparsed("count", Integer.toString(alt.sharedIps().size())));
    }
}
