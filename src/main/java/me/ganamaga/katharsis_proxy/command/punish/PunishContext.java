package me.ganamaga.katharsis_proxy.command.punish;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.ganamaga.katharsis_proxy.config.PunishmentsConfig;
import me.ganamaga.katharsis_proxy.database.PlayerHistoryRepository;
import me.ganamaga.katharsis_proxy.entity.IssuerRef;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.messaging.CompanionRegistry;
import me.ganamaga.katharsis_proxy.punish.PunishmentService;
import me.ganamaga.katharsis_proxy.punish.PunishmentText;
import me.ganamaga.katharsis_proxy.punish.ScopeResolver;
import me.ganamaga.katharsis_proxy.punish.TargetResolver;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

/**
 * What every punishment command needs, in one place.
 *
 * @param plugin     the plugin, to schedule work under
 * @param config     the {@code punishments} section, as currently loaded
 * @param companions which servers run the companion plugin, which enforces mutes
 */
public record PunishContext(Object plugin, ProxyServer proxy, Logger logger, PunishmentService service,
                            TargetResolver targets, PlayerHistoryRepository history, ScopeResolver scopes,
                            PunishmentText text, Supplier<PunishmentsConfig> config, CompanionRegistry companions) {

    public Messages messages() {
        return text.messages();
    }

    /** Runs {@code task} off the command thread; punishments block on the database. */
    public void async(Runnable task) {
        proxy.getScheduler().buildTask(plugin, task).schedule();
    }

    /** The backend servers as {@code velocity.toml} names them. */
    public List<String> serverNames() {
        return proxy.getAllServers().stream().map(RegisteredServer::getServerInfo)
                .map(info -> info.getName()).toList();
    }

    /** Who {@code source} is, as recorded on what they issue. */
    public static IssuerRef issuer(CommandSource source) {
        if (source instanceof Player player) {
            return new IssuerRef(player.getUniqueId(), player.getUsername(), IssuerRef.Source.GAME);
        }
        return IssuerRef.console();
    }
}
