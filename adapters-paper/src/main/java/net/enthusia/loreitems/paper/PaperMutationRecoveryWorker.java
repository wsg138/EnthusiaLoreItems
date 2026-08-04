package net.enthusia.loreitems.paper;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.PendingMutationRepository;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperMutationRecoveryWorker implements AutoCloseable {
    private static final long INITIAL_DELAY_TICKS = 1L;
    private static final long POLL_INTERVAL_TICKS = 100L;
    private static final int MIN_RECOVERY_LIMIT = 1;

    private final Plugin plugin;
    private final PendingMutationRepository repository;
    private final int recoveryLimit;
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean();

    private volatile boolean closed;
    private BukkitTask task;

    public PaperMutationRecoveryWorker(
            Plugin plugin,
            PendingMutationRepository repository,
            int recoveryLimit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        if (recoveryLimit < MIN_RECOVERY_LIMIT) {
            throw new IllegalArgumentException("recoveryLimit must be positive");
        }
        this.recoveryLimit = recoveryLimit;
    }

    public void start() {
        if (closed || task != null) {
            throw new IllegalStateException("Mutation recovery worker cannot be started");
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::requestRun,
                INITIAL_DELAY_TICKS,
                POLL_INTERVAL_TICKS);
    }

    void requestRun() {
        if (closed || !recoveryInFlight.compareAndSet(false, true)) {
            return;
        }
        CompletionStage<Integer> recovery;
        try {
            recovery = Objects.requireNonNull(
                    repository.moveExpiredClaimsToReview(
                            Instant.now(), recoveryLimit),
                    "mutation recovery stage");
        } catch (RuntimeException exception) {
            recoveryInFlight.set(false);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not submit expired mutation recovery.",
                    exception);
            return;
        }
        recovery.whenComplete((recovered, failure) -> {
            recoveryInFlight.set(false);
            if (failure != null) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not recover expired item-mutation claims.",
                        unwrap(failure));
            } else if (recovered == null) {
                plugin.getLogger().severe(
                        "Expired item-mutation recovery returned no result.");
            } else if (recovered > 0) {
                plugin.getLogger().warning(
                        "Moved " + recovered
                                + " expired item-mutation claims to REVIEW_REQUIRED.");
            }
        });
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
        if (current != null) {
            current.cancel();
        }
    }
}
