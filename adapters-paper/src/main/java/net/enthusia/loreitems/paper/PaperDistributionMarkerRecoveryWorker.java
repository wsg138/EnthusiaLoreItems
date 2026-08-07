package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.PageRequest;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Periodically reconciles filesystem campaign markers from DB-authoritative state in bounded pages. */
public final class PaperDistributionMarkerRecoveryWorker implements AutoCloseable {
    private static final long INITIAL_DELAY_TICKS = 1L;
    private static final long PERIOD_TICKS = 200L;

    private final Plugin plugin;
    private final PaperDistributionMarkerReconciler reconciler;
    private final int pageSize;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    private volatile boolean closed;
    private volatile PageRequest nextRequest;
    private BukkitTask task;

    public PaperDistributionMarkerRecoveryWorker(
            Plugin plugin,
            PaperDistributionMarkerReconciler reconciler,
            int pageSize) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        if (pageSize < 1 || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside the supported bounded range");
        }
        this.pageSize = pageSize;
        nextRequest = PageRequest.first(pageSize);
    }

    public void start() {
        requirePrimaryThread();
        if (closed || task != null) {
            throw new IllegalStateException("Distribution marker recovery worker cannot be started");
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::reconcileNextPage, INITIAL_DELAY_TICKS, PERIOD_TICKS);
    }

    public void wake() {
        if (closed) {
            return;
        }
        scheduleMain(this::reconcileNextPage);
    }

    private void reconcileNextPage() {
        requirePrimaryThread();
        if (closed || !inFlight.compareAndSet(false, true)) {
            return;
        }
        PageRequest request = nextRequest;
        try {
            reconciler.reconcile(request).whenComplete((page, throwable) -> {
                if (throwable != null || page == null) {
                    logFailure(throwable);
                    inFlight.set(false);
                    return;
                }
                logUnsafeEntries(page);
                nextRequest = page.nextPage() == null
                        ? PageRequest.first(pageSize)
                        : page.nextPage();
                inFlight.set(false);
            });
        } catch (RuntimeException exception) {
            inFlight.set(false);
            logFailure(exception);
        }
    }

    private void logUnsafeEntries(DistributionMarkerReconciliationPage page) {
        for (DistributionMarkerReconciliationPage.Entry entry : page.entries()) {
            if (entry.status() == DistributionMarkerReconciliationPage.Status.MISSING_SOURCE
                    || entry.status() == DistributionMarkerReconciliationPage.Status.FAILED) {
                plugin.getLogger().warning(
                        "Distribution marker reconciliation requires attention for "
                                + entry.campaignId() + ": " + entry.detail());
            }
        }
    }

    private void logFailure(Throwable throwable) {
        if (throwable == null) {
            plugin.getLogger().severe("Distribution marker reconciliation returned no result.");
            return;
        }
        plugin.getLogger().log(
                Level.SEVERE,
                "Distribution marker reconciliation failed; durable DB state remains authoritative.",
                throwable);
    }

    private void scheduleMain(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            if (!closed) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not schedule distribution marker reconciliation.",
                        exception);
            }
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Distribution marker worker lifecycle must run on the server thread");
        }
    }

    @Override
    public void close() {
        closed = true;
        BukkitTask activeTask = task;
        if (activeTask != null) {
            activeTask.cancel();
        }
    }
}
