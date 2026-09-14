package me.ganamaga.katharsis_paper.command;

import me.ganamaga.katharsis_paper.CompanionMessages;
import me.ganamaga.katharsis_paper.menu.LanguageMenuModel;
import me.ganamaga.katharsis_paper.menu.LanguageMenuView;
import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * Opens the language selector.
 *
 * <p>Replaces the proxy's own {@code /language}: the proxy unregisters its copy
 * as soon as this server announces itself.</p>
 */
public final class LanguageCommand implements CommandExecutor {

    private final Plugin plugin;
    private final ProxyClient client;
    private final LanguageMenuView view;
    private final CompanionMessages messages;

    public LanguageCommand(Plugin plugin, ProxyClient client, LanguageMenuView view, CompanionMessages messages) {
        this.plugin = plugin;
        this.client = client;
        this.view = view;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.playersOnly());
            return true;
        }

        client.requestMenu(player).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                reportFailure(player, response, failure);
                return;
            }
            // Inventories may only be opened from the server thread.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    view.open(player, LanguageMenuModel.from(response));
                }
            });
        });
        return true;
    }

    private void reportFailure(Player player, Object response, Throwable failure) {
        player.sendMessage(messages.proxyUnreachable());
        if (failure != null) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not fetch the language menu for " + player.getName(), failure);
        } else {
            plugin.getLogger().warning("The proxy refused the language menu for "
                    + player.getName() + ": " + response);
        }
    }
}
