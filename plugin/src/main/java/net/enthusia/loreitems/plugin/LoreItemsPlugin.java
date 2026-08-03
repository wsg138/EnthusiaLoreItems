package net.enthusia.loreitems.plugin;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.enthusia.loreitems.application.AtomicConfiguration;
import net.enthusia.loreitems.application.CreateDefinitionResult;
import net.enthusia.loreitems.application.CreateDefinitionUseCase;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PersistingCreateDefinitionUseCase;
import net.enthusia.loreitems.application.PersistingExternalDeliveryUseCase;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.paper.CreateDefinitionCommandExecutor;
import net.enthusia.loreitems.paper.PaperHeldItemDefinitionSnapshotter;
import net.enthusia.loreitems.sqlite.BoundedDatabaseExecutor;
import net.enthusia.loreitems.sqlite.MigrationRunner;
import net.enthusia.loreitems.sqlite.SQLiteConnectionFactory;
import net.enthusia.loreitems.sqlite.SQLiteDirectDeliveryRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import net.enthusia.loreitems.sqlite.SQLiteUnitOfWork;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

// Paper plugins may own bounded lifecycle executors; this is not a J2EE web application.
@SuppressWarnings("PMD.DoNotUseThreads")
public final class LoreItemsPlugin extends JavaPlugin {
    private static final String STOPPING_RELOAD_DETAIL =
            "The plugin is stopping; configuration reload was not applied.";

    private final AtomicReference<LoreItemsServiceV1> serviceDelegate =
            new AtomicReference<>(new UnavailableService("Foundation storage has not started."));
    private final AtomicReference<AtomicConfiguration> configuration =
            new AtomicReference<>(new AtomicConfiguration(FoundationConfiguration.defaults()));
    private final AtomicReference<CreateDefinitionUseCase> createDefinitionDelegate =
            new AtomicReference<>(unavailableCreateDefinitionUseCase());
    private final LoreItemsServiceV1 registeredService = new DelegatingService(serviceDelegate);
    private final CreateDefinitionUseCase registeredCreateDefinitionUseCase =
            request -> createDefinitionDelegate.get().create(request);
    private final Object lifecycleLock = new Object();
    private final Set<CompletableFuture<AtomicConfiguration.ReloadResult>> pendingReloads =
            ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor lifecycleExecutor = new ThreadPoolExecutor(
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

    private volatile SQLiteStorageRuntime storageRuntime;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        registerCommands();
        getServer().getServicesManager().register(
                LoreItemsServiceV1.class,
                registeredService,
                this,
                ServicePriority.Normal);
        Path dataDirectory = getDataFolder().toPath();
        try {
            lifecycleExecutor.execute(() -> initialize(dataDirectory));
        } catch (RejectedExecutionException exception) {
            publishUnavailableServices("Foundation lifecycle queue rejected startup.");
            getLogger().severe("LoreItems startup was rejected: " + exception.getMessage());
        }
        getLogger().info("Foundation bootstrap enabled; durable storage is initializing off-thread.");
    }

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("loreitems"), "plugin.yml must declare the loreitems command");
        command.setExecutor(new CreateDefinitionCommandExecutor(
                this,
                registeredCreateDefinitionUseCase,
                new PaperHeldItemDefinitionSnapshotter()));
    }

    @Override
    public void onDisable() {
        synchronized (lifecycleLock) {
            stopping = true;
            serviceDelegate.set(new UnavailableService("The plugin is stopping."));
            createDefinitionDelegate.set(unavailableCreateDefinitionUseCase());
        }
        getServer().getServicesManager().unregisterAll(this);
        lifecycleExecutor.shutdownNow();
        failPendingReloads(STOPPING_RELOAD_DETAIL);

        SQLiteStorageRuntime runtime = storageRuntime;
        if (runtime != null) {
            int timeoutSeconds = configuration.get().current().databaseShutdownTimeoutSeconds();
            boolean drained = runtime.close(Duration.ofSeconds(timeoutSeconds));
            if (!drained) {
                getLogger().warning("Database shutdown exceeded the configured bounded drain timeout.");
            }
        }
    }

    public CompletionStage<AtomicConfiguration.ReloadResult> reloadFoundationConfiguration() {
        CompletableFuture<AtomicConfiguration.ReloadResult> result = new CompletableFuture<>();
        pendingReloads.add(result);
        result.whenComplete((ignored, throwable) -> pendingReloads.remove(result));
        synchronized (lifecycleLock) {
            if (stopping) {
                result.complete(new AtomicConfiguration.ReloadResult(false, STOPPING_RELOAD_DETAIL));
                return result;
            }
            try {
                lifecycleExecutor.execute(() -> reloadConfiguration(result));
            } catch (RejectedExecutionException exception) {
                result.complete(new AtomicConfiguration.ReloadResult(
                        false, "The lifecycle queue is not accepting reload work."));
            }
        }
        return result;
    }

    private void reloadConfiguration(
            CompletableFuture<AtomicConfiguration.ReloadResult> result) {
        if (stopping || result.isDone()) {
            result.complete(new AtomicConfiguration.ReloadResult(false, STOPPING_RELOAD_DETAIL));
            return;
        }
        try {
            FoundationConfiguration candidate =
                    new FoundationConfigurationLoader(getDataFolder().toPath()).loadOrCreate();
            synchronized (lifecycleLock) {
                if (stopping || result.isDone()) {
                    result.complete(new AtomicConfiguration.ReloadResult(
                            false, STOPPING_RELOAD_DETAIL));
                    return;
                }
                result.complete(configuration.get().replace(candidate));
            }
        } catch (Exception exception) {
            result.complete(new AtomicConfiguration.ReloadResult(
                    false,
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
        }
    }

    private void failPendingReloads(String detail) {
        AtomicConfiguration.ReloadResult failure =
                new AtomicConfiguration.ReloadResult(false, detail);
        pendingReloads.forEach(future -> future.complete(failure));
    }

    private void initialize(Path dataDirectory) {
        try {
            FoundationConfiguration loaded =
                    new FoundationConfigurationLoader(dataDirectory).loadOrCreate();
            if (!publishConfiguration(loaded)) {
                return;
            }
            SQLiteStorageRuntime runtime = createStorageRuntime(dataDirectory, loaded);
            if (!publishStorageRuntime(runtime)) {
                runtime.close(Duration.ZERO);
                return;
            }
            initializeStorage(runtime, loaded);
        } catch (Exception exception) {
            handleInitializationFailure(exception);
        }
    }

    private boolean publishConfiguration(FoundationConfiguration loaded) {
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            configuration.set(new AtomicConfiguration(loaded));
            return true;
        }
    }

    private static SQLiteStorageRuntime createStorageRuntime(
            Path dataDirectory, FoundationConfiguration loaded) {
        MetricsPort metrics = MetricsPort.noOp();
        BoundedDatabaseExecutor databaseExecutor = new BoundedDatabaseExecutor(
                "loreitems-database", loaded.databaseQueueCapacity(), metrics);
        return new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(
                        dataDirectory.resolve("loreitems.db"), loaded.databaseBusyTimeoutMillis()),
                new MigrationRunner(),
                databaseExecutor,
                metrics);
    }

    private boolean publishStorageRuntime(SQLiteStorageRuntime runtime) {
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            storageRuntime = runtime;
            return true;
        }
    }

    private void initializeStorage(
            SQLiteStorageRuntime runtime, FoundationConfiguration loaded) {
        SQLiteStorageRuntime.StartupResult startup = runtime.start().toCompletableFuture().join();
        if (stopping) {
            return;
        }
        if (startup.state() != StorageState.READ_WRITE) {
            publishDegradedService(startup);
            return;
        }
        SQLiteDirectDeliveryRepository repository = new SQLiteDirectDeliveryRepository(runtime);
        recoverExpiredClaims(repository, loaded.deliveryClaimBatchSize());
        LoreItemsServiceV1 deliveryService = new FoundationLoreItemsService(
                new PersistingExternalDeliveryUseCase(repository, Clock.systemUTC()));
        CreateDefinitionUseCase createDefinitionUseCase = new PersistingCreateDefinitionUseCase(
                new SQLiteUnitOfWork(runtime), Clock.systemUTC());
        if (publishWritableServices(deliveryService, createDefinitionUseCase)) {
            getLogger().info(
                    "Durable storage is active; held-item definition creation is available. "
                            + "Physical inventory delivery remains deferred.");
        }
    }

    private void publishDegradedService(SQLiteStorageRuntime.StartupResult startup) {
        if (publishUnavailableServices(
                "LoreItems is in degraded read-only mode: " + startup.detail())) {
            getLogger().severe(
                    "LoreItems entered degraded read-only mode: " + startup.detail());
        }
    }

    private boolean publishWritableServices(
            LoreItemsServiceV1 service,
            CreateDefinitionUseCase createDefinitionUseCase) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(createDefinitionUseCase, "createDefinitionUseCase");
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            serviceDelegate.set(service);
            createDefinitionDelegate.set(createDefinitionUseCase);
            return true;
        }
    }

    private boolean publishUnavailableServices(String detail) {
        Objects.requireNonNull(detail, "detail");
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            serviceDelegate.set(new UnavailableService(detail));
            createDefinitionDelegate.set(unavailableCreateDefinitionUseCase());
            return true;
        }
    }

    private void recoverExpiredClaims(
            SQLiteDirectDeliveryRepository repository, int recoveryLimit) {
        int recovered = repository.moveExpiredClaimsToReview(Instant.now(), recoveryLimit)
                .toCompletableFuture()
                .join();
        if (recovered > 0) {
            getLogger().warning(
                    "Moved " + recovered + " expired delivery claims to REVIEW_REQUIRED.");
        }
        if (recovered == recoveryLimit) {
            getLogger().warning(
                    "The bounded startup recovery batch was full; additional expired "
                            + "delivery claims may remain for later recovery.");
        }
    }

    private void handleInitializationFailure(Exception exception) {
        publishUnavailableServices(
                "Foundation initialization failed: " + safeMessage(exception));
        getLogger().severe(
                "LoreItems foundation initialization failed; writes remain unavailable: "
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
    }

    private static CreateDefinitionUseCase unavailableCreateDefinitionUseCase() {
        return request -> CompletableFuture.completedFuture(
                CreateDefinitionResult.serviceUnavailable());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "no detail" : message;
    }

    private static final class DelegatingService implements LoreItemsServiceV1 {
        private final AtomicReference<LoreItemsServiceV1> delegate;

        private DelegatingService(AtomicReference<LoreItemsServiceV1> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public CompletionStage<LoreDeliveryResult> queueDelivery(
                String definitionKey, UUID playerId, String externalOperationId) {
            return delegate.get().queueDelivery(definitionKey, playerId, externalOperationId);
        }
    }

    private static final class UnavailableService implements LoreItemsServiceV1 {
        private final String detail;

        private UnavailableService(String detail) {
            this.detail = Objects.requireNonNull(detail, "detail");
        }

        @Override
        public CompletionStage<LoreDeliveryResult> queueDelivery(
                String definitionKey, UUID playerId, String externalOperationId) {
            String safeOperationId = externalOperationId == null ? "" : externalOperationId.strip();
            if (definitionKey == null
                    || definitionKey.isBlank()
                    || playerId == null
                    || safeOperationId.isEmpty()) {
                return CompletableFuture.completedFuture(new LoreDeliveryResult(
                        LoreDeliveryStatus.VALIDATION_FAILURE,
                        safeOperationId,
                        "Definition key, player UUID, and external operation ID are required."));
            }
            return CompletableFuture.completedFuture(new LoreDeliveryResult(
                    LoreDeliveryStatus.SERVICE_UNAVAILABLE,
                    safeOperationId,
                    detail));
        }
    }
}
