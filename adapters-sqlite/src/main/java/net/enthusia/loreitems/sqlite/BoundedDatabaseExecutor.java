package net.enthusia.loreitems.sqlite;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.enthusia.loreitems.application.MetricsPort;

public final class BoundedDatabaseExecutor {
    private final ThreadPoolExecutor executor;
    private final MetricsPort metrics;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public BoundedDatabaseExecutor(String threadName, int queueCapacity, MetricsPort metrics) {
        Objects.requireNonNull(threadName, "threadName");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!accepting.get()) {
            future.completeExceptionally(new RejectedExecutionException("Database executor is stopping"));
            return future;
        }
        try {
            executor.execute(() -> runTask(task, future));
            metrics.setGauge("database.queue.depth", executor.getQueue().size());
        } catch (RejectedExecutionException exception) {
            metrics.increment("database.queue.rejected");
            future.completeExceptionally(exception);
        }
        return future;
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public boolean shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        accepting.set(false);
        executor.shutdown();
        boolean terminated = await(timeout);
        if (!terminated) {
            metrics.increment("database.shutdown.forced");
            executor.shutdownNow();
        }
        return terminated;
    }

    private <T> void runTask(Callable<T> task, CompletableFuture<T> future) {
        long started = System.nanoTime();
        try {
            future.complete(task.call());
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        } finally {
            metrics.recordDurationNanos("database.task.duration", System.nanoTime() - started);
            metrics.setGauge("database.queue.depth", executor.getQueue().size());
        }
    }

    private boolean await(Duration timeout) {
        try {
            return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
