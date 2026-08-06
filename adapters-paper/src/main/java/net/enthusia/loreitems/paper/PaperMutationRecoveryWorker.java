package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.PendingMutationRepository;
import net.enthusia.loreitems.application.PersistingTemplateUpdateExecutionUseCase;
import net.enthusia.loreitems.application.TemplateUpdateExecutionStore;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Bounded mutation subsystem: expired-claim recovery plus natural-access execution. */
public final class PaperMutationRecoveryWorker implements AutoCloseable {
    private static final int MIN_RECOVERY_LIMIT = 1;
    private static final long INITIAL_DELAY_TICKS = 1L;
    private static final long RECOVERY_PERIOD_TICKS = 100L;
    private static final Duration TEMPLATE_UPDATE_CLAIM_LEASE = Duration.ofSeconds(30L);

    private final Plugin plugin;
    private final PendingMutationRepository repository;
    private final int recoveryLimit;
    private final PaperTemplateUpdateCoordinator templateUpdateCoordinator;
    private final PaperTemplateUpdateAccessRegistry templateUpdateAccessRegistry;
    private final PaperTemplateUpdateListener templateUpdateListener;
    private final PaperEntityTemplateUpdateListener entityTemplateUpdateListener;
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean();

    private BukkitTask task;
    private volatile boolean closed;

    public PaperMutationRecoveryWorker(
            Plugin plugin,
            PendingMutationRepository repository,
            int recoveryLimit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        if (recoveryLimit < MIN_RECOVERY_LIMIT) {
            throw new IllegalArgumentException("recoveryLimit must be positive");
        }
        if (!(repository instanceof TemplateUpdateExecutionStore templateStore)) {
            throw new IllegalArgumentException(
                    "repository must also provide template-update execution storage");
        }
        this.recoveryLimit = recoveryLimit;
        TemplateUpdateExecutionUseCase useCase = new PersistingTemplateUpdateExecutionUseCase(
                templateStore,
                Clock.systemUTC(),
                TEMPLATE_UPDATE_CLAIM_LEASE);
        this.templateUpdateCoordinator = new PaperTemplateUpdateCoordinator(
                plugin,
                useCase,
                new PaperTemplateUpdateOperator(),
                recoveryLimit);
        this.templateUpdateAccessRegistry = new PaperTemplateUpdateAccessRegistry();
        this.templateUpdateListener = new PaperTemplateUpdateListener(
                plugin,
                templateUpdateCoordinator,
                templateUpdateAccessRegistry,
                recoveryLimit);
        this.entityTemplateUpdateListener = new PaperEntityTemplateUpdateListener(
                plugin,
                templateUpdateAccessRegistry,
                recoveryLimit);
    }

    public void start() {
        if (closed || task != null) {
            throw new IllegalStateException("Mutation recovery worker cannot be started");
        }
        templateUpdateListener.start();
        try {
            entityTemplateUpdateListener.start();
            task = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::requestRun,
                    INITIAL_DELAY_TICKS,
                    RECOVERY_PERIOD_TICKS);
        } catch (RuntimeException exception) {
            entityTemplateUpdateListener.close();
            templateUpdateListener.close();
            templateUpdateCoordinator.close();
            throw exception;
        }
    }

    public void wakeAccessible() {
        if (closed) {
            return;
        }
        templateUpdateListener.wakeAccessible();
        entityTemplateUpdateListener.wakeAccessible();
    }

    void requestRun() {
        if (closed || !recoveryInFlight.compareAndSet(false, true)) {
            return;
        }
        CompletionStage<Integer> stage;
        try {
            stage = Objects.requireNonNull(
                    repository.moveExpiredClaimsToReview(Instant.now(), recoveryLimit),
                    "mutation recovery stage");
        } catch (RuntimeException exception) {
            recoveryInFlight.set(false);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start bounded expired mutation recovery.",
                    exception);
            return;
        }
        stage.whenComplete((recovered, throwable) -> {
            recoveryInFlight.set(false);
            if (throwable != null) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not recover expired item-mutation claims.",
                        unwrap(throwable));
                return;
            }
            if (recovered == null) {
                plugin.getLogger().severe(
                        "Expired mutation recovery completed without a result.");
                return;
            }
            if (recovered > 0) {
                plugin.getLogger().warning(
                        "Moved " + recovered
                                + " expired item-mutation claims to REVIEW_REQUIRED.");
            }
            if (recovered == recoveryLimit) {
                plugin.getLogger().warning(
                        "The bounded mutation-recovery batch was full; more expired claims "
                                + "may remain for the next pass.");
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
        entityTemplateUpdateListener.close();
        templateUpdateListener.close();
        templateUpdateCoordinator.close();
        BukkitTask current = task;
        if (current != null) {
            current.cancel();
        }
    }
}
