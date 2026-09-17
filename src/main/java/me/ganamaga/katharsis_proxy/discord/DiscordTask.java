package me.ganamaga.katharsis_proxy.discord;

import discord4j.core.GatewayDiscordClient;
import reactor.core.publisher.Mono;

/**
 * Work for the Discord bot, run on its thread against the logged-in client.
 *
 * <p>Return the reactive chain rather than subscribing to it yourself: the bot
 * waits for it to finish, which keeps tasks in order and lets the returned
 * future carry the result.</p>
 *
 * <pre>{@code
 * bot.submit(client -> client.getChannelById(Snowflake.of(channelId))
 *         .ofType(MessageChannel.class)
 *         .flatMap(channel -> channel.createMessage("The proxy is up.")));
 * }</pre>
 */
@FunctionalInterface
public interface DiscordTask<T> {

    Mono<T> run(GatewayDiscordClient client);
}
