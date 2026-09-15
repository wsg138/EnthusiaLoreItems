package net.enthusia.loreitems.plugin;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;

/** Owns bounded asynchronous shutdown mechanics for the plugin lifecycle. */
@SuppressWarnings("PMD.DoNotUseThreads")
final class LoreItemsShutdownSupport {
    private static final Duration MIN_TRACKING_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5L);

    private LoreItemsShutdownSupport() {}

    static ThreadPoolExecutor createLifecycleExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4),
                runnable -> {
                    Thread thread = new Thread(runnable, "loreitems-lifecycle");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    static Duration trackingShutdownTimeout(Duration databaseShutdownTimeout) {
        Objects.requireNonNull(databaseShutdownTimeout, "databaseShutdownTimeout");
        return databaseShutdownTimeout.compareTo(MIN_TRACKING_SHUTDOWN_TIMEOUT) >= 0
                ? databaseShutdownTimeout
                : MIN_TRACKING_SHUTDOWN_TIMEOUT;
    }

    static boolean trackingQuiesced(CompletionStage<Void> trackingQuiescence) {
        CompletableFuture<Void> future = Objects.requireNonNull(
                        trackingQuiescence, "trackingQuiescence")
                .toCompletableFuture();
        return future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
    }

    static void start(
            Logger logger,
            CompletionStage<Void> trackingQuiescence,
            SQLiteStorageRuntime runtime,
            ThreadPoolExecutor executor,
            Duration timeout,
            Runnable completion) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(completion, "completion");
        Thread thread = new Thread(
                () -> finish(logger, trackingQuiescence, runtime, executor, timeout, completion),
                "loreitems-shutdown");
        try {
            thread.start();
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Could not start asynchronous LoreItems shutdown; same-instance re-enable "
                            + "will remain fenced for safety.",
                    exception);
        }
    }

    private static void finish(
            Logger logger,
            CompletionStage<Void> trackingQuiescence,
            SQLiteStorageRuntime runtime,
            ThreadPoolExecutor executor,
            Duration timeout,
            Runnable completion) {
        try {
            awaitTrackingQuiescence(logger, trackingQuiescence, trackingShutdownTimeout(timeout));
            closeRuntime(logger, runtime, timeout);
            awaitLifecycleTermination(logger, executor, timeout);
        } finally {
            completion.run();
        }
    }

    private static void closeRuntime(
            Logger logger,
            SQLiteStorageRuntime runtime,
            Duration timeout) {
        if (runtime != null && !runtime.close(timeout)) {
            logger.warning("Database shutdown exceeded the configured bounded drain timeout.");
        }
    }

    private static void awaitTrackingQuiescence(
            Logger logger,
            CompletionStage<Void> trackingQuiescence,
            Duration timeout) {
        try {
            trackingQuiescence.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while draining lore-item tracking evidence.", exception);
        } catch (TimeoutException exception) {
            logger.warning(
                    "Timed out while draining lore-item tracking evidence; database shutdown "
                            + "will continue with its bounded executor drain.");
        } catch (ExecutionException exception) {
            logger.log(Level.WARNING, "Lore-item tracking quiescence failed unexpectedly.", exception.getCause());
        }
    }

    private static void awaitLifecycleTermination(
            Logger logger,
            ThreadPoolExecutor executor,
            Duration timeout) {
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                logger.warning(
                        "Lifecycle worker shutdown exceeded the configured bounded drain timeout; "
                                + "same-instance re-enable remains fenced until it terminates.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                    Level.WARNING,
                    "Interrupted while waiting for the LoreItems lifecycle worker to stop.",
                    exception);
        }
    }
}
