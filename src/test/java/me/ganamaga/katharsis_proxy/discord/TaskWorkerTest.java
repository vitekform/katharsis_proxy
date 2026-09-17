package me.ganamaga.katharsis_proxy.discord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskWorkerTest {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    /** A client that records what happened to it, and connects once released. */
    private static final class FakeConnector implements TaskWorker.Connector<String> {

        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        volatile Exception failure;

        FakeConnector connected() {
            release.countDown();
            return this;
        }

        @Override
        public String connect() throws Exception {
            release.await();
            if (failure != null) {
                throw failure;
            }
            events.add("connect");
            return "client";
        }

        @Override
        public void disconnect(String client) {
            events.add("disconnect");
        }
    }

    private final List<TaskWorker<String>> workers = new ArrayList<>();

    @AfterEach
    void stopWorkers() {
        workers.forEach(worker -> worker.stop(Duration.ofMillis(100)));
    }

    private TaskWorker<String> started(int capacity, FakeConnector connector) {
        TaskWorker<String> worker = new TaskWorker<>(
                LoggerFactory.getLogger(TaskWorkerTest.class), "test-worker", capacity, connector);
        workers.add(worker);
        worker.start();
        return worker;
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return thrown.getCause();
    }

    @Test
    @DisplayName("runs tasks against the client, in order, on its own thread")
    void runsInOrderOffThread() throws Exception {
        FakeConnector connector = new FakeConnector().connected();
        TaskWorker<String> worker = started(10, connector);

        List<Integer> order = new CopyOnWriteArrayList<>();
        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int index = i;
            results.add(worker.submit(client -> {
                order.add(index);
                return client + " on " + Thread.currentThread().getName();
            }));
        }

        for (CompletableFuture<String> result : results) {
            assertEquals("client on test-worker", result.get(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of(0, 1, 2, 3, 4), order);
        assertNotEquals("test-worker", Thread.currentThread().getName());
        assertTrue(worker.isConnected());
    }

    @Test
    @DisplayName("tasks submitted while connecting wait for the connection")
    void queuesWhileConnecting() throws Exception {
        FakeConnector connector = new FakeConnector();
        TaskWorker<String> worker = started(10, connector);

        assertTrue(worker.isAccepting());
        assertFalse(worker.isConnected());
        CompletableFuture<String> result = worker.submit(client -> {
            connector.events.add("task");
            return client;
        });
        assertFalse(result.isDone());

        connector.release.countDown();
        assertEquals("client", result.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("connect", "task"), connector.events);
    }

    @Test
    @DisplayName("a failing task fails only its own future")
    void failureIsIsolated() throws Exception {
        TaskWorker<String> worker = started(10, new FakeConnector().connected());

        CompletableFuture<String> failed = worker.submit(client -> {
            throw new IllegalStateException("boom");
        });
        CompletableFuture<String> after = worker.submit(client -> "still running");

        assertInstanceOf(IllegalStateException.class, failureOf(failed));
        assertEquals("still running", after.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a full queue rejects instead of blocking the caller")
    void fullQueueRejects() {
        // Still connecting, so nothing is taken off the queue.
        TaskWorker<String> worker = started(1, new FakeConnector());

        CompletableFuture<String> queued = worker.submit(client -> client);
        CompletableFuture<String> rejected = worker.submit(client -> client);

        assertFalse(queued.isDone());
        assertInstanceOf(RejectedExecutionException.class, failureOf(rejected));
        assertEquals(1, worker.pending());
    }

    @Test
    @DisplayName("rejects tasks before start and after stop")
    void rejectsWhenNotRunning() {
        FakeConnector connector = new FakeConnector().connected();
        TaskWorker<String> worker = new TaskWorker<>(
                LoggerFactory.getLogger(TaskWorkerTest.class), "test-worker", 10, connector);
        workers.add(worker);

        assertInstanceOf(RejectedExecutionException.class, failureOf(worker.submit(client -> client)));

        worker.start();
        worker.stop(STOP_TIMEOUT);

        assertFalse(worker.isAccepting());
        assertInstanceOf(RejectedExecutionException.class, failureOf(worker.submit(client -> client)));
        assertThrows(IllegalStateException.class, worker::start);
    }

    @Test
    @DisplayName("stopping runs everything already queued, then disconnects")
    void stopDrainsThenDisconnects() throws Exception {
        FakeConnector connector = new FakeConnector();
        TaskWorker<String> worker = started(10, connector);

        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int index = i;
            results.add(worker.submit(client -> {
                connector.events.add("task " + index);
                return client;
            }));
        }
        connector.release.countDown();
        worker.stop(STOP_TIMEOUT);

        for (CompletableFuture<String> result : results) {
            assertTrue(result.isDone());
            assertEquals("client", result.get());
        }
        assertEquals(List.of("connect", "task 0", "task 1", "task 2", "disconnect"), connector.events);
        worker.stop(STOP_TIMEOUT);
        assertEquals(1, connector.events.stream().filter("disconnect"::equals).count(), "stopping twice is harmless");
    }

    @Test
    @DisplayName("a failed connection rejects the queued tasks and every later one")
    void connectionFailureRejects() {
        FakeConnector connector = new FakeConnector();
        connector.failure = new IllegalArgumentException("bad token");
        TaskWorker<String> worker = started(10, connector);

        CompletableFuture<String> queued = worker.submit(client -> client);
        connector.release.countDown();

        assertInstanceOf(RejectedExecutionException.class, failureOf(queued));
        assertFalse(worker.isAccepting());
        assertInstanceOf(RejectedExecutionException.class, failureOf(worker.submit(client -> client)));
        assertFalse(connector.events.contains("disconnect"), "nothing was connected");
    }

    @Test
    @DisplayName("stopping gives up on a hung task, fails the rest, and still disconnects")
    void stopAbandonsHungTask() throws InterruptedException {
        FakeConnector connector = new FakeConnector().connected();
        TaskWorker<String> worker = started(10, connector);

        CountDownLatch hung = new CountDownLatch(1);
        CompletableFuture<String> stuck = worker.submit(client -> {
            hung.countDown();
            new CountDownLatch(1).await();
            return client;
        });
        CompletableFuture<String> behind = worker.submit(client -> client);

        assertTrue(hung.await(5, TimeUnit.SECONDS), "the task started");
        worker.stop(Duration.ofMillis(200));

        assertInstanceOf(InterruptedException.class, failureOf(stuck));
        assertInstanceOf(RejectedExecutionException.class, failureOf(behind));
        assertTrue(connector.events.contains("disconnect"));
    }
}
