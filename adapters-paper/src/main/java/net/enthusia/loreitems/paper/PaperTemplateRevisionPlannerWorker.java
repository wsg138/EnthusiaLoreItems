package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchResult;
import net.enthusia.loreitems.application.TemplateRevisionRolloutCandidate;
import net.enthusia.loreitems.application.TemplateRevisionRolloutUseCase;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Plans one bounded durable rollout batch per pass until every active instance is queued. */
public final class PaperTemplateRevisionPlannerWorker implements AutoCloseable {
    private static final long INITIAL_DELAY_TICKS = 1L;
    private static final long PERIOD_TICKS = 20L;

    private final Plugin plugin;
    private final TemplateRevisionRolloutUseCase useCase;
    private final int batchLimit;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private BukkitTask task;
    private volatile boolean closed;

    public PaperTemplateRevisionPlannerWorker(
            Plugin plugin, TemplateRevisionRolloutUseCase useCase, int batchLimit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        if (batchLimit < 1 || batchLimit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("batchLimit is outside bounded page limits");
        }
        this.batchLimit = batchLimit;
    }

    public void start() {
        if (closed || task != null) {
            throw new IllegalStateException("Template rollout planner cannot be started");
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::requestRun, INITIAL_DELAY_TICKS, PERIOD_TICKS);
    }

    public void wake() {
        if (closed) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, this::requestRun);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not wake template rollout planning during shutdown.",
                    exception);
        }
    }

    void requestRun() {
        if (closed || !inFlight.compareAndSet(false, true)) {
            return;
        }
        CompletionStage<Page<TemplateRevisionRolloutCandidate>> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.listIncomplete(PageRequest.first(1)),
                    "incomplete rollout stage");
        } catch (RuntimeException exception) {
            inFlight.set(false);
            report("start template rollout discovery", exception);
            return;
        }
        stage.whenComplete(this::discovered);
    }

    private void discovered(
            Page<TemplateRevisionRolloutCandidate> page, Throwable failure) {
        if (failure != null) {
            inFlight.set(false);
            report("discover incomplete template rollouts", failure);
            return;
        }
        if (page == null || page.items().isEmpty()) {
            inFlight.set(false);
            return;
        }
        TemplateRevisionRolloutCandidate candidate = page.items().getFirst();
        CompletionStage<TemplateRevisionRolloutBatchResult> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.scheduleNextBatch(candidate, batchLimit),
                    "rollout scheduling stage");
        } catch (RuntimeException exception) {
            inFlight.set(false);
            report("schedule a template rollout batch", exception);
            return;
        }
        stage.whenComplete((result, throwable) -> scheduled(result, throwable));
    }

    private void scheduled(TemplateRevisionRolloutBatchResult result, Throwable failure) {
        inFlight.set(false);
        if (failure != null) {
            report("schedule a template rollout batch", failure);
            return;
        }
        if (result == null) {
            plugin.getLogger().severe("Template rollout scheduling returned no result.");
            return;
        }
        if (result.hasMore()) {
            wake();
        }
    }

    private void report(String operation, Throwable throwable) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Could not " + operation + "; durable unscheduled instances remain discoverable.",
                unwrap(throwable));
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException exception && exception.getCause() != null
                ? exception.getCause()
                : throwable;
    }

    @Override
    public void close() {
        closed = true;
        BukkitTask current = task;
        if (current != null) {
            current.cancel();
            task = null;
        }
    }
}
