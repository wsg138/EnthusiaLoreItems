package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.enthusia.loreitems.application.MetricsPort;
import org.junit.jupiter.api.Test;

class BoundedDatabaseExecutorTest {
    private static final String RUNNING = "running";

    @Test
    void saturatedQueueRejectsWithoutExceedingConfiguredCapacityAndReportsMetrics() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        BoundedDatabaseExecutor executor =
                new BoundedDatabaseExecutor("bounded-executor-test", 1, metrics);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> running = executor.submit(() -> {
            started.countDown();
            assertTrue(release.await(1, TimeUnit.SECONDS));
            return RUNNING;
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        CompletableFuture<String> queued = executor.submit(() -> "queued");
        CompletableFuture<String> rejected = executor.submit(() -> "rejected");

        assertEquals(1, executor.queueDepth());
        assertEquals(1L, metrics.gauge("database.queue.depth"));
        CompletionException failure = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        assertEquals(1L, metrics.counter("database.queue.rejected"));

        release.countDown();
        assertEquals(RUNNING, running.get(1, TimeUnit.SECONDS));
        assertEquals("queued", queued.get(1, TimeUnit.SECONDS));
        assertTrue(executor.shutdown(Duration.ofSeconds(1)));
        assertEquals(0L, metrics.gauge("database.queue.depth"));
        assertTrue(metrics.durationCount("database.task.duration") >= 2L);
    }

    @Test
    void forcedShutdownCompletesAbandonedQueuedFutures() throws Exception {
        BoundedDatabaseExecutor executor =
                new BoundedDatabaseExecutor("bounded-executor-test", 2, MetricsPort.noOp());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> running = executor.submit(() -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.interrupted();
                }
            }
            return RUNNING;
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        CompletableFuture<String> queued = executor.submit(() -> "queued");
        assertFalse(executor.shutdown(Duration.ofMillis(1)));

        CompletionException failure = assertThrows(CompletionException.class, queued::join);
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());

        release.countDown();
        assertEquals(RUNNING, running.get(1, TimeUnit.SECONDS));
    }

    private static final class RecordingMetrics implements MetricsPort {
        private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> durationCounts = new ConcurrentHashMap<>();

        @Override
        public void setGauge(String name, long value) {
            gauges.computeIfAbsent(name, ignored -> new AtomicLong()).set(value);
        }

        @Override
        public void increment(String name) {
            counters.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void recordDurationNanos(String name, long durationNanos) {
            durationCounts.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
        }

        long gauge(String name) {
            return gauges.getOrDefault(name, new AtomicLong()).get();
        }

        long counter(String name) {
            return counters.getOrDefault(name, new AtomicLong()).get();
        }

        long durationCount(String name) {
            return durationCounts.getOrDefault(name, new AtomicLong()).get();
        }
    }
}
