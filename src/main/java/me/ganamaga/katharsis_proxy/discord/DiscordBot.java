package me.ganamaga.katharsis_proxy.discord;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * The Discord bot, logged in and running on its own thread.
 *
 * <p>The rest of the plugin never touches the Discord client directly. It hands
 * the bot {@link DiscordTask}s through {@link #submit}, from any thread, and
 * the bot runs them one after another. Submitting never blocks, so it is safe
 * from event handlers and commands.</p>
 */
public final class DiscordBot {

    static final String THREAD_NAME = "katharsis-discord";

    /** Tasks waiting beyond this are rejected rather than piling up during an outage. */
    static final int QUEUE_CAPACITY = 1_000;

    /** Longest a single task may hold up the queue. */
    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration LOGOUT_TIMEOUT = Duration.ofSeconds(5);

    /** How long shutdown waits for queued tasks to finish. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final Logger logger;

    private volatile TaskWorker<GatewayDiscordClient> worker;

    public DiscordBot(Logger logger) {
        this.logger = logger;
    }

    /**
     * Starts the bot thread, which logs in with {@code token} before running
     * anything. Tasks submitted meanwhile wait for the login. A failed login
     * is logged, and every task is rejected from then on.
     */
    public void start(String token) {
        if (worker != null) {
            throw new IllegalStateException("The Discord bot has already been started");
        }
        TaskWorker<GatewayDiscordClient> created =
                new TaskWorker<>(logger, THREAD_NAME, QUEUE_CAPACITY, new DiscordConnector(logger, token));
        worker = created;
        created.start();
    }

    /**
     * Queues {@code task} to run on the bot thread.
     *
     * <p>The future completes on the bot thread, so move anything heavy, or
     * anything touching the proxy, onto the Velocity scheduler. It fails with
     * {@link RejectedExecutionException} when the bot is off, stopped, failed
     * to log in, or has {@value #QUEUE_CAPACITY} tasks waiting, and with the
     * task's own error, or a timeout after 30 seconds, if it ran and failed.
     * Rejections are not logged, since a disabled bot is normal.</p>
     */
    public <T> CompletableFuture<T> submit(DiscordTask<T> task) {
        Objects.requireNonNull(task, "task");
        TaskWorker<GatewayDiscordClient> current = worker;
        if (current == null) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("The Discord bot is not running"));
        }
        return current.submit(client -> task.run(client).block(TASK_TIMEOUT));
    }

    /** Whether submitted tasks will be run, possibly after the login completes. */
    public boolean isAccepting() {
        TaskWorker<GatewayDiscordClient> current = worker;
        return current != null && current.isAccepting();
    }

    /** Whether the bot is logged in. */
    public boolean isConnected() {
        TaskWorker<GatewayDiscordClient> current = worker;
        return current != null && current.isConnected();
    }

    /**
     * Stops accepting tasks, gives the queue up to ten seconds to drain, and
     * logs out. Safe to call when the bot was never started.
     */
    public void stop() {
        TaskWorker<GatewayDiscordClient> current = worker;
        if (current != null) {
            current.stop(STOP_TIMEOUT);
        }
    }

    /** A class rather than a record, whose generated toString would print the token. */
    private static final class DiscordConnector implements TaskWorker.Connector<GatewayDiscordClient> {

        private final Logger logger;
        private final String token;

        DiscordConnector(Logger logger, String token) {
            this.logger = logger;
            this.token = token;
        }

        @Override
        public GatewayDiscordClient connect() {
            logger.info("Logging in to Discord...");
            // No timeout: Discord4J keeps retrying an unreachable gateway on
            // its own, and stop() interrupts the wait.
            GatewayDiscordClient client = DiscordClient.create(token).login().block();
            if (client == null) {
                throw new IllegalStateException("The Discord login completed without a client");
            }
            logger.info("Logged in to Discord as user {}.", client.getSelfId().asString());
            return client;
        }

        @Override
        public void disconnect(GatewayDiscordClient client) {
            client.logout().block(LOGOUT_TIMEOUT);
            logger.info("Logged out of Discord.");
        }
    }
}
