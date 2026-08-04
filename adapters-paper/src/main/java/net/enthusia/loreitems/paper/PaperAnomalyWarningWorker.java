package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingMetricsSource;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperAnomalyWarningWorker
        implements AnomalyWarningSink, TrackingMetricsSource, AutoCloseable {
    private static final long TICKS_PER_SECOND = 20L;
    private static final int MIN_POSITIVE_VALUE = 1;
    private static final int TRACKING_BUDGET_PER_TICK = 16;

    private final Plugin plugin;
    private final LoreItemsAdministrationUseCase useCase;
    private final int pageSize;
    private final long intervalTicks;
    private final Object queryLock = new Object();
    private final TrackingMetrics trackingMetrics = new TrackingMetrics();

    private BukkitTask task;
    private PaperPhysicalTrackingListener trackingListener;
    private boolean queryInFlight;
    private boolean rerunRequested;
    private volatile boolean closed;

    public PaperAnomalyWarningWorker(
            Plugin plugin,
            LoreItemsAdministrationUseCase useCase,
            int intervalSeconds,
            int pageSize) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        if (intervalSeconds < MIN_POSITIVE_VALUE) {
            throw new IllegalArgumentException("intervalSeconds must be positive");
        }
        if (pageSize < MIN_POSITIVE_VALUE || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside supported bounds");
        }
        this.pageSize = pageSize;
        this.intervalTicks = Math.multiplyExact(intervalSeconds, TICKS_PER_SECOND);
    }

    public void start() {
        TrackingObservationUseCase trackingUseCase = resolveTrackingUseCase();
        PaperPhysicalTrackingListener physical = new PaperPhysicalTrackingListener(
                plugin,
                () -> trackingUseCase,
                () -> TRACKING_BUDGET_PER_TICK,
                trackingMetrics);
        try {
            physical.start();
            synchronized (queryLock) {
                if (closed) {
                    throw new IllegalStateException("Anomaly warning worker is closed");
                }
                if (task != null) {
                    throw new IllegalStateException("Anomaly warning worker is already started");
                }
                task = plugin.getServer().getScheduler().runTaskTimer(
                        plugin,
                        this::requestWarning,
                        intervalTicks,
                        intervalTicks);
                trackingListener = physical;
            }
        } catch (RuntimeException exception) {
            physical.close();
            throw exception;
        }
        requestWarning();
    }

    private TrackingObservationUseCase resolveTrackingUseCase() {
        ItemAnomalyObservationUseCase service = plugin.getServer()
                .getServicesManager()
                .load(ItemAnomalyObservationUseCase.class);
        if (service instanceof TrackingObservationUseCase tracking) {
            return tracking;
        }
        throw new IllegalStateException(
                "Registered item-observation service does not support physical tracking");
    }

    @Override
    public TrackingMetrics.Snapshot trackingMetrics() {
        return trackingMetrics.snapshot();
    }

    @Override
    public void requestWarning() {
        synchronized (queryLock) {
            if (closed) {
                return;
            }
            if (queryInFlight) {
                rerunRequested = true;
                return;
            }
            queryInFlight = true;
        }
        startQuery();
    }

    private void startQuery() {
        CompletionStage<Page<InstanceAnomaly>> query;
        try {
            query = Objects.requireNonNull(
                    useCase.listWarningAnomalies(PageRequest.first(pageSize)),
                    "warning anomaly query returned null");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start the lore-item warning query.",
                    exception);
            finishQuery();
            return;
        }
        query.whenComplete((page, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not query active lore-item warning anomalies.",
                        unwrap(failure));
            } else if (!closed && page != null && !page.items().isEmpty()) {
                scheduleWarning(page);
            } else if (page == null) {
                plugin.getLogger().severe(
                        "The lore-item warning query completed without a result.");
            }
            finishQuery();
        });
    }

    private void finishQuery() {
        boolean runAgain;
        synchronized (queryLock) {
            queryInFlight = false;
            runAgain = !closed && rerunRequested;
            rerunRequested = false;
            if (runAgain) {
                queryInFlight = true;
            }
        }
        if (runAgain) {
            startQuery();
        }
    }

    private void scheduleWarning(Page<InstanceAnomaly> page) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> sendWarning(page));
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule lore-item anomaly warnings during shutdown.",
                    exception);
        }
    }

    private void sendWarning(Page<InstanceAnomaly> page) {
        if (closed) {
            return;
        }
        long duplicateCount = page.items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.DUPLICATE_INSTANCE)
                .count();
        long malformedCount = page.items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.MALFORMED_STACK)
                .count();
        String count = page.hasMore()
                ? "at least " + page.items().size()
                : Integer.toString(page.items().size());
        String message = "[LoreItems] " + count
                + " unresolved identity anomalies require review (duplicates="
                + duplicateCount + ", malformed=" + malformedCount
                + "). Use /loreitems anomalies.";
        plugin.getServer().getConsoleSender().sendMessage(message);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) {
                player.sendMessage(message);
            }
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    @Override
    public void close() {
        BukkitTask current;
        PaperPhysicalTrackingListener physical;
        synchronized (queryLock) {
            closed = true;
            rerunRequested = false;
            current = task;
            physical = trackingListener;
            trackingListener = null;
        }
        if (current != null) {
            current.cancel();
        }
        if (physical != null) {
            physical.close();
        }
    }
}
