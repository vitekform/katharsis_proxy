package me.ganamaga.katharsis_proxy.punish;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.entity.Punishment;
import me.ganamaga.katharsis_proxy.entity.PunishmentType;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.function.BiFunction;

/**
 * Tells staff online with {@link PunishPermissions#NOTIFY} about every
 * punishment issued, unless it was issued quietly, and every one lifted.
 */
public final class StaffNotifier implements PunishmentObserver {

    private final ProxyServer proxy;
    private final PunishmentText text;

    public StaffNotifier(ProxyServer proxy, PunishmentText text) {
        this.proxy = proxy;
        this.text = text;
    }

    @Override
    public void issued(Punishment punishment) {
        if (punishment.silent()) {
            return;
        }
        broadcast((language, viewer) -> punishment.type() == PunishmentType.KICK
                ? text.messages().render(language, MessageKey.PUNISH_STAFF_KICKED,
                        Placeholder.unparsed("issuer", punishment.issuer().name()),
                        Placeholder.component("player", target(language, punishment, viewer)),
                        Placeholder.unparsed("reason", punishment.reason()),
                        Placeholder.unparsed("id", Long.toString(punishment.id())))
                : text.messages().render(language, MessageKey.PUNISH_STAFF_ISSUED,
                        Placeholder.unparsed("issuer", punishment.issuer().name()),
                        Placeholder.component("player", target(language, punishment, viewer)),
                        Placeholder.component("type", text.type(language, punishment.type())),
                        Placeholder.component("scope", text.scope(language, punishment.scope())),
                        Placeholder.component("duration", text.length(language, punishment)),
                        Placeholder.unparsed("reason", punishment.reason()),
                        Placeholder.unparsed("id", Long.toString(punishment.id()))));
    }

    @Override
    public void revoked(Punishment punishment) {
        broadcast((language, viewer) -> text.messages().render(language, MessageKey.PUNISH_STAFF_REVOKED,
                Placeholder.unparsed("issuer", punishment.revocation().by().name()),
                Placeholder.component("player", target(language, punishment, viewer)),
                Placeholder.component("type", text.type(language, punishment.type())),
                Placeholder.unparsed("id", Long.toString(punishment.id()))));
    }

    /**
     * Tells staff {@code key}, rendered in each one's language, behind no
     * prefix: staff messages carry their own marker.
     */
    public void announce(MessageKey key, TagResolver... placeholders) {
        broadcast((language, viewer) -> text.messages().render(language, key, placeholders));
    }

    private void broadcast(BiFunction<String, Player, Component> render) {
        for (Player player : proxy.getAllPlayers()) {
            if (player.hasPermission(PunishPermissions.NOTIFY)) {
                player.sendMessage(render.apply(text.messages().languageOf(player), player));
            }
        }
    }

    /** The target's name; for a bare address, only to staff who may see addresses. */
    private Component target(String language, Punishment punishment, Player viewer) {
        if (punishment.targetUuid() == null && !viewer.hasPermission(PunishPermissions.SEE_IP)) {
            return text.messages().render(language, MessageKey.PUNISH_IP_HIDDEN);
        }
        return Component.text(punishment.targetName());
    }
}
