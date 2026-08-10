package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.DestructiveOperationStore;
import net.enthusia.loreitems.application.DestructiveOperationStoreProvider;
import net.enthusia.loreitems.application.PendingMutationRepository;
import net.enthusia.loreitems.application.PendingMutationReviewStore;
import net.enthusia.loreitems.application.PendingMutationReviewUseCase;
import net.enthusia.loreitems.application.PersistingDestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingDestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.PersistingPendingMutationReviewUseCase;
import net.enthusia.loreitems.application.PersistingTemplateUpdateExecutionUseCase;
import net.enthusia.loreitems.application.TemplateUpdateExecutionStore;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Bounded mutation subsystem: expired-claim recovery plus natural-access execution. */
public final class PaperMutationRecoveryWorker implements AutoCloseable {
    private static final int MIN_RECOVERY_LIMIT = 1;
    private static final long INITIAL_DELAY_TICKS = 1L;
    private static final long RECOVERY_PERIOD_TICKS = 100L;
    private static final Duration MUTATION_CLAIM_LEASE = Duration.ofSeconds(30L);

    private final Plugin plugin;
    private final PendingMutationRepository repository;
    private final Optional<DestructiveOperationStore> destructiveStore;
    private final Optional<DestructiveAdministrationUseCase> destructiveAdministration;
    private final Optional<PendingMutationReviewUseCase> mutationReviewUseCase;
    private final int recoveryLimit;
    private final PaperTemplateUpdateCoordinator templateUpdateCoordinator;
    private final PaperTemplateUpdateAccessRegistry templateUpdateAccessRegistry;
    private final PaperTemplateUpdateListener templateUpdateListener;
    private final PaperEntityTemplateUpdateListener entityTemplateUpdateListener;
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean();

    private BukkitTask task;
    private LoreItemsMutationReviewCommandExecutor reviewCommandExecutor;
    private boolean destructiveServicesRegistered;
    private volatile boolean closed;

    public PaperMutationRecoveryWorker(
            Plugin plugin,
            PendingMutationRepository repository,
            int recoveryLimit) {
        this(plugin, repository, optionalDestructiveStore(repository), recoveryLimit);
    }

    public PaperMutationRecoveryWorker(
            Plugin plugin,
            PendingMutationRepository repository,
            DestructiveOperationStore destructiveStore,
            int recoveryLimit) {
        this(
                plugin,
                repository,
                Optional.of(Objects.requireNonNull(destructiveStore, "destructiveStore")),
                recoveryLimit);
    }

    private PaperMutationRecoveryWorker(
            Plugin plugin,
            PendingMutationRepository repository,
            Optional<DestructiveOperationStore> destructiveStore,
            int recoveryLimit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.destructiveStore = Objects.requireNonNull(destructiveStore, "destructiveStore");
        if (recoveryLimit < MIN_RECOVERY_LIMIT) {
            throw new IllegalArgumentException("recoveryLimit must be positive");
        }
        if (!(repository instanceof TemplateUpdateExecutionStore templateStore)) {
            throw new IllegalArgumentException(
                    "repository must also provide template-update execution storage");
        }
        this.recoveryLimit = recoveryLimit;
        Clock clock = Clock.systemUTC();
        this.destructiveAdministration = destructiveStore.map(
                store -> new PersistingDestructiveAdministrationUseCase(store, clock));
        this.mutationReviewUseCase = repository instanceof PendingMutationReviewStore reviewStore
                ? Optional.of(new PersistingPendingMutationReviewUseCase(reviewStore, clock))
                : Optional.empty();
        TemplateUpdateExecutionUseCase useCase = new PersistingTemplateUpdateExecutionUseCase(
                templateStore,
                clock,
                MUTATION_CLAIM_LEASE);
        this.templateUpdateCoordinator = createCoordinator(useCase, clock);
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
        registerDestructiveServices();
        registerReviewCommand();
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
            closeReviewCommand();
            unregisterDestructiveServices();
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
        CompletionStage<RecoveryCounts> stage;
        try {
            CompletionStage<Integer> templateRecovery = Objects.requireNonNull(
                    repository.moveExpiredClaimsToReview(Instant.now(), recoveryLimit),
                    "template recovery stage");
            CompletionStage<Integer> destructiveRecovery = destructiveStore
                    .map(this::recoverDestructiveClaims)
                    .orElseGet(() -> CompletableFuture.completedFuture(0));
            stage = templateRecovery.thenCombine(
                    destructiveRecovery,
                    RecoveryCounts::new);
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
            reportRecovery("template-update", recovered.templateUpdates());
            reportRecovery("destructive-removal", recovered.destructiveRemovals());
        });
    }

    private CompletionStage<Integer> recoverDestructiveClaims(DestructiveOperationStore store) {
        return Objects.requireNonNull(
                store.moveExpiredClaimsToReview(Instant.now(), recoveryLimit),
                "destructive recovery stage");
    }

    private PaperTemplateUpdateCoordinator createCoordinator(
            TemplateUpdateExecutionUseCase useCase,
            Clock clock) {
        if (destructiveStore.isEmpty()) {
            return new PaperTemplateUpdateCoordinator(
                    plugin,
                    useCase,
                    new PaperTemplateUpdateOperator(),
                    recoveryLimit);
        }
        return new PaperTemplateUpdateCoordinator(
                plugin,
                useCase,
                new PaperTemplateUpdateOperator(),
                new PersistingDestructiveRemovalExecutionUseCase(
                        destructiveStore.orElseThrow(),
                        clock,
                        MUTATION_CLAIM_LEASE),
                new PaperDestructiveRemovalOperator(),
                recoveryLimit);
    }

    private void registerReviewCommand() {
        if (mutationReviewUseCase.isEmpty()) {
            return;
        }
        if (!(plugin instanceof JavaPlugin javaPlugin)) {
            throw new IllegalStateException("Mutation-review command requires a JavaPlugin owner");
        }
        PluginCommand command = Objects.requireNonNull(
                javaPlugin.getCommand("loreitemsreview"),
                "plugin.yml must declare loreitemsreview");
        LoreItemsMutationReviewCommandExecutor executor =
                new LoreItemsMutationReviewCommandExecutor(
                        plugin,
                        mutationReviewUseCase::orElseThrow,
                        this::wakeAccessible);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        reviewCommandExecutor = executor;
    }

    private void closeReviewCommand() {
        LoreItemsMutationReviewCommandExecutor executor = reviewCommandExecutor;
        if (executor != null) {
            executor.close();
            reviewCommandExecutor = null;
        }
    }

    private void registerDestructiveServices() {
        if (destructiveAdministration.isEmpty()) {
            return;
        }
        ServicesManager services = plugin.getServer().getServicesManager();
        DestructiveAdministrationUseCase administration = destructiveAdministration.orElseThrow();
        services.register(
                DestructiveAdministrationUseCase.class,
                administration,
                plugin,
                ServicePriority.Normal);
        try {
            services.register(
                    PaperMutationRecoveryWorker.class,
                    this,
                    plugin,
                    ServicePriority.Normal);
            destructiveServicesRegistered = true;
        } catch (RuntimeException exception) {
            services.unregister(DestructiveAdministrationUseCase.class, administration);
            throw exception;
        }
    }

    private void unregisterDestructiveServices() {
        if (!destructiveServicesRegistered) {
            return;
        }
        ServicesManager services = plugin.getServer().getServicesManager();
        services.unregister(PaperMutationRecoveryWorker.class, this);
        services.unregister(
                DestructiveAdministrationUseCase.class,
                destructiveAdministration.orElseThrow());
        destructiveServicesRegistered = false;
    }

    private static Optional<DestructiveOperationStore> optionalDestructiveStore(
            PendingMutationRepository repository) {
        Objects.requireNonNull(repository, "repository");
        if (repository instanceof DestructiveOperationStoreProvider provider) {
            return Optional.of(Objects.requireNonNull(
                    provider.destructiveOperationStore(),
                    "destructive operation store"));
        }
        return Optional.empty();
    }

    private void reportRecovery(String kind, int recovered) {
        if (recovered > 0) {
            plugin.getLogger().warning(
                    "Moved " + recovered + " expired " + kind
                            + " claims to REVIEW_REQUIRED.");
        }
        if (recovered == recoveryLimit) {
            plugin.getLogger().warning(
                    "The bounded " + kind
                            + " recovery batch was full; more expired claims may remain.");
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
        entityTemplateUpdateListener.close();
        templateUpdateListener.close();
        templateUpdateCoordinator.close();
        BukkitTask current = task;
        if (current != null) {
            current.cancel();
        }
        closeReviewCommand();
        unregisterDestructiveServices();
    }

    private record RecoveryCounts(int templateUpdates, int destructiveRemovals) {}
}
