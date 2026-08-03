package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperAnomalyWarningWorker
        implements AnomalyWarningSink, AutoCloseable {
    private static final long TICKS_PER_SECOND = 20L;

    private final Plugin plugin;
    private final LoreItemsAdministrationUseCase useCase;
    private final int pageSize;
    private final long intervalTicks;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    private volatile BukkitTask task;
    private volatile boolean closed;

    public PaperAnomalyWarningWorker(
            Plugin plugin,
            LoreItemsAdministrationUseCase useCase,
            int intervalSeconds,
            int pageSize) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        if (intervalSeconds < 1) {
            throw new IllegalArgumentException("intervalSeconds must be positive");
        }
        if (pageSize < 1 || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside supported bounds");
        }
        this.pageSize = pageSize;
        this.intervalTicks = Math.multiplyExact(intervalSeconds, TICKS_PER_SECOND);
    }

    public void start() {
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
        requestWarning();
    }

    @Override
    public void requestWarning() {
        if (closed || !inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            useCase.listWarningAnomalies(PageRequest.first(pageSize))
                    .whenComplete((page, failure) -> {
                        inFlight.set(false);
                        if (failure != null) {
                            plugin.getLogger().log(
                                    Level.SEVERE,
                                    "Could not query active lore-item warning anomalies.",
                                    unwrap(failure));
                            return;
                        }
                        if (!closed && !page.items().isEmpty()) {
                            scheduleWarning(page);
                        }
                    });
        } catch (RuntimeException exception) {
            inFlight.set(false);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start the lore-item warning query.",
                    exception);
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
        closed = true;
        BukkitTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }
}
