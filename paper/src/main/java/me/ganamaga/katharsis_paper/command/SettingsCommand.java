package me.ganamaga.katharsis_paper.command;

import me.ganamaga.katharsis_paper.CompanionMessages;
import me.ganamaga.katharsis_paper.menu.SettingsMenuModel;
import me.ganamaga.katharsis_paper.menu.SettingsMenuView;
import me.ganamaga.katharsis_paper.protocol.ProxyResponse;
import me.ganamaga.katharsis_paper.protocol.ProxyClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Opens the settings menu, and asks new players about Discord chat.
 *
 * <p>Both are drawn from one {@code get_settings_menu} reply, so like the
 * language selector they hold no state and no text of their own.</p>
 */
public final class SettingsCommand implements CommandExecutor {

    private final Plugin plugin;
    private final ProxyClient client;
    private final SettingsMenuView view;
    private final CompanionMessages messages;

    public SettingsCommand(Plugin plugin, ProxyClient client, SettingsMenuView view, CompanionMessages messages) {
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

        open(player);
        return true;
    }

    /** Fetches {@code player}'s settings and shows them. Callable from any thread. */
    public void open(Player player) {
        fetchThen(player, view::open);
    }

    /**
     * Asks {@code player} whether Discord chat should be shown to them, as the
     * last step of their first join. Callable from any thread.
     */
    public void openDiscordChatPrompt(Player player) {
        fetchThen(player, view::openDiscordChatPrompt);
    }

    private void fetchThen(Player player, BiConsumer<Player, SettingsMenuModel> show) {
        client.requestSettings(player).whenComplete((response, failure) -> {
            if (failure != null || response == null || !response.success()) {
                reportFailure(player, response, failure);
                return;
            }
            // Inventories may only be opened from the server thread.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    show.accept(player, SettingsMenuModel.from(response));
                }
            });
        });
    }

    private void reportFailure(Player player, ProxyResponse response, Throwable failure) {
        player.sendMessage(messages.proxyUnreachable());
        if (failure != null) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not fetch the settings for " + player.getName(), failure);
        } else {
            plugin.getLogger().warning("The proxy refused the settings for "
                    + player.getName() + ": " + response);
        }
    }
}
