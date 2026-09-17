package me.ganamaga.katharsis_proxy.discord;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * One background thread that holds a connected client and runs submitted tasks
 * against it, one at a time and in the order they were submitted.
 *
 * <p>Any thread may submit, and submitting never blocks: a full queue rejects
 * the task instead. Tasks submitted while the client is still connecting wait
 * until it is ready. Stopping lets the queue drain, disconnects, and fails
 * whatever did not get to run.</p>
 *
 * <p>Nothing here knows about Discord, so the queueing can be tested without a
 * bot.</p>
 *
 * @param <C> the client tasks run against
 */
public final class TaskWorker<C> {

    /** Opens and closes the client. Both are called on the worker thread. */
    public interface Connector<C> {

        C connect() throws Exception;

        void disconnect(C client) throws Exception;
    }

    @FunctionalInterface
    public interface Task<C, T> {

        T run(C client) throws Exception;
    }

    private enum State { NEW, CONNECTING, RUNNING, STOPPING, STOPPED }

    /** How often an idle worker checks whether it has been asked to stop. */
    private static final long IDLE_POLL_MS = 250L;

    private final Logger logger;
    private final String name;
    private final Connector<C> connector;
    private final BlockingQueue<Job<C, ?>> queue;

    /*
     * Guards state and thread. Submitting checks the state and enqueues under
     * it, so once STOPPING is set no task can slip in behind the final drain.
     */
    private final Object lock = new Object();
    private State state = State.NEW;
    private Thread thread;

    /** Set when stop() gave up waiting and interrupted the worker. */
    private volatile boolean abandoned;

    public TaskWorker(Logger logger, String name, int capacity, Connector<C> connector) {
        this.logger = logger;
        this.name = name;
        this.connector = connector;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Starts the worker thread, which connects before running anything. */
    public void start() {
        synchronized (lock) {
            if (state != State.NEW) {
                throw new IllegalStateException(name + " has already been started");
            }
            state = State.CONNECTING;
            thread = new Thread(this::run, name);
            // Never keep the proxy's JVM alive on its own.
            thread.setDaemon(true);
            // Threads the client library starts inherit this, and it has to see
            // the plugin's classes rather than the proxy's.
            thread.setContextClassLoader(TaskWorker.class.getClassLoader());
            thread.start();
        }
    }

    /**
     * Queues {@code task} to run on the worker thread.
     *
     * <p>The returned future completes on the worker thread, so callbacks
     * attached without an executor run there and hold up the tasks behind
     * them. It fails with {@link RejectedExecutionException} if the worker is
     * not running or its queue is full.</p>
     */
    public <T> CompletableFuture<T> submit(Task<C, T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (lock) {
            if (state != State.CONNECTING && state != State.RUNNING) {
                result.completeExceptionally(new RejectedExecutionException(name + " is not running"));
            } else if (!queue.offer(new Job<>(task, result))) {
                result.completeExceptionally(new RejectedExecutionException(name + " has a full queue"));
            }
        }
        return result;
    }

    /** Whether tasks are being accepted, including while still connecting. */
    public boolean isAccepting() {
        synchronized (lock) {
            return state == State.CONNECTING || state == State.RUNNING;
        }
    }

    /** Whether the client is connected and tasks are running. */
    public boolean isConnected() {
        synchronized (lock) {
            return state == State.RUNNING;
        }
    }

    /** Tasks waiting to run, not counting one that is running. */
    public int pending() {
        return queue.size();
    }

    /**
     * Stops accepting tasks and waits up to {@code timeout} for the queue to
     * drain. If it does not, the worker is interrupted, the task it is on and
     * everything still queued fail, and it gets up to {@code timeout} again to
     * disconnect. Safe to call more than once, or on a worker never started.
     */
    public void stop(Duration timeout) {
        Thread worker;
        synchronized (lock) {
            switch (state) {
                case NEW -> {
                    state = State.STOPPED;
                    return;
                }
                case STOPPED -> {
                    return;
                }
                case CONNECTING, RUNNING -> state = State.STOPPING;
                case STOPPING -> {
                    // Another caller is already stopping it; wait alongside.
                }
            }
            worker = thread;
        }

        try {
            if (!worker.join(timeout)) {
                logger.warn("{} did not finish its tasks within {} ms; dropping {} queued task(s).",
                        name, timeout.toMillis(), queue.size());
                abandon(worker);
                worker.join(timeout);
            }
        } catch (InterruptedException e) {
            abandon(worker);
            Thread.currentThread().interrupt();
        }
    }

    private void abandon(Thread worker) {
        abandoned = true;
        worker.interrupt();
    }

    private void run() {
        C client;
        try {
            client = connector.connect();
        } catch (Exception e) {
            if (abandoned) {
                logger.info("{} was stopped before it finished connecting.", name);
            } else {
                logger.error("{} could not connect; its tasks will be rejected.", name, e);
            }
            finish();
            return;
        }

        synchronized (lock) {
            if (state == State.CONNECTING) {
                state = State.RUNNING;
            }
        }
        try {
            process(client);
        } finally {
            disconnect(client);
            finish();
        }
    }

    private void process(C client) {
        while (!abandoned) {
            Job<C, ?> job;
            try {
                job = queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // Only stop() means it. A task leaving the flag set does not.
                continue;
            }
            if (job != null) {
                job.run(client, logger, name);
            } else if (drained()) {
                return;
            }
        }
    }

    /** Whether the worker was asked to stop and has nothing left to run. */
    private boolean drained() {
        synchronized (lock) {
            return state == State.STOPPING && queue.isEmpty();
        }
    }

    private void disconnect(C client) {
        // An abandoning interrupt would otherwise cut the disconnect short too.
        Thread.interrupted();
        try {
            connector.disconnect(client);
        } catch (Exception e) {
            logger.warn("{} did not disconnect cleanly.", name, e);
        }
    }

    private void finish() {
        List<Job<C, ?>> leftover = new ArrayList<>();
        synchronized (lock) {
            state = State.STOPPED;
            queue.drainTo(leftover);
        }
        // Completed outside the lock, since callers' callbacks run inline.
        for (Job<C, ?> job : leftover) {
            job.result().completeExceptionally(
                    new RejectedExecutionException(name + " stopped before running this task"));
        }
        if (!leftover.isEmpty()) {
            logger.warn("{} dropped {} task(s) that never ran.", name, leftover.size());
        }
    }

    private record Job<C, T>(Task<C, T> task, CompletableFuture<T> result) {

        void run(C client, Logger logger, String name) {
            try {
                result.complete(task.run(client));
            } catch (Throwable e) {
                // Errors too: letting one escape would end the thread and leave
                // every queued future hanging.
                logger.warn("A task on {} failed.", name, e);
                result.completeExceptionally(e);
            }
        }
    }
}
