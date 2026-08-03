package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import net.enthusia.loreitems.application.MetricsPort;
import org.junit.jupiter.api.Test;

class BoundedDatabaseExecutorTest {
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
            return "running";
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        CompletableFuture<String> queued = executor.submit(() -> "queued");
        assertFalse(executor.shutdown(Duration.ofMillis(1)));

        CompletionException failure = assertThrows(CompletionException.class, queued::join);
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());

        release.countDown();
        assertEquals("running", running.get(1, TimeUnit.SECONDS));
    }
}
