package me.ganamaga.katharsis_proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.ganamaga.katharsis_proxy.discord.DiscordAccountLinking;
import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.i18n.MessageKey;
import me.ganamaga.katharsis_proxy.i18n.Messages;
import me.ganamaga.katharsis_proxy.link.AccountLinkService;
import me.ganamaga.katharsis_proxy.link.RedeemResult;
import me.ganamaga.katharsis_proxy.util.CooldownManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /link <code>}: links the player's Minecraft account to the Discord
 * account that was given the code by {@code /link} on Discord.
 */
public final class LinkCommand implements SimpleCommand {

    public static final String NAME = "link";

    private static final String PERMISSION = "katharsis.command.link";

    /** Spaces out attempts, so codes cannot be guessed by trying them in bulk. */
    private static final Duration ATTEMPT_COOLDOWN = Duration.ofSeconds(3);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Messages messages;
    private final AccountLinkService links;
    private final DiscordAccountLinking discord;
    private final CooldownManager cooldowns = new CooldownManager(ATTEMPT_COOLDOWN);

    public LinkCommand(Object plugin, ProxyServer proxy, Logger logger, Messages messages,
                       AccountLinkService links, DiscordAccountLinking discord) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.messages = messages;
        this.links = links;
        this.discord = discord;
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Everyone may use the command unless the node is explicitly denied.
        return invocation.source().getPermissionValue(PERMISSION) != Tristate.FALSE;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            messages.send(invocation.source(), MessageKey.PLAYERS_ONLY);
            return;
        }

        String language = messages.languageOf(player);
        UUID playerId = player.getUniqueId();

        Optional<AccountLink> cached = links.cachedLink(playerId);
        if (cached.isPresent()) {
            player.sendMessage(messages.prefixed(language, MessageKey.LINK_ALREADY_LINKED,
                    Placeholder.unparsed("discord", cached.get().discordUsername())));
            return;
        }

        String[] arguments = invocation.arguments();
        if (arguments.length != 1) {
            player.sendMessage(messages.prefixed(language, MessageKey.LINK_USAGE));
            return;
        }

        Duration remaining = cooldowns.remaining(playerId);
        if (!remaining.isZero()) {
            player.sendMessage(messages.prefixed(language, MessageKey.ON_COOLDOWN,
                    Placeholder.component("time", messages.duration(language, remaining))));
            return;
        }
        if (!links.isAvailable()) {
            player.sendMessage(messages.prefixed(language, MessageKey.LINK_UNAVAILABLE));
            return;
        }

        cooldowns.start(playerId);
        String code = arguments[0];
        // Off the command thread: redeeming is a database transaction.
        proxy.getScheduler().buildTask(plugin, () -> redeem(player, language, code)).schedule();
    }

    private void redeem(Player player, String language, String code) {
        RedeemResult result;
        try {
            result = links.redeem(player.getUniqueId(), player.getUsername(), code);
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Could not link the account of {} ({}).", player.getUsername(), player.getUniqueId(), e);
            player.sendMessage(messages.prefixed(language, MessageKey.LINK_UNAVAILABLE));
            return;
        }

        switch (result) {
            case RedeemResult.Linked linked -> {
                AccountLink link = linked.link();
                logger.info("Linked {} ({}) to Discord user {} ({}).", link.playerName(), link.playerUuid(),
                        link.discordUsername(), link.discordUserId());
                player.sendMessage(messages.prefixed(language, MessageKey.LINK_SUCCESS,
                        Placeholder.unparsed("discord", link.discordUsername())));
                discord.grantLinkedRole(link);
            }
            case RedeemResult.PlayerAlreadyLinked already -> player.sendMessage(
                    messages.prefixed(language, MessageKey.LINK_ALREADY_LINKED,
                            Placeholder.unparsed("discord", already.link().discordUsername())));
            case RedeemResult.InvalidCode ignored -> player.sendMessage(
                    messages.prefixed(language, MessageKey.LINK_INVALID_CODE));
            case RedeemResult.DiscordAlreadyLinked ignored -> player.sendMessage(
                    messages.prefixed(language, MessageKey.LINK_DISCORD_TAKEN));
        }
    }
}
