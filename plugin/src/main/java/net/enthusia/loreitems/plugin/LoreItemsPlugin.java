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
import net.enthusia.loreitems.application.AdoptHeldItemUseCase;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.AtomicConfiguration;
import net.enthusia.loreitems.application.CreateDefinitionResult;
import net.enthusia.loreitems.application.CreateDefinitionUseCase;
import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PersistingAdoptHeldItemUseCase;
import net.enthusia.loreitems.application.PersistingCreateDefinitionUseCase;
import net.enthusia.loreitems.application.PersistingDirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.PersistingDisplayItemObservationUseCase;
import net.enthusia.loreitems.application.PersistingExternalDeliveryUseCase;
import net.enthusia.loreitems.application.PersistingItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.PersistingLoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingVoidLossUseCase;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionResult;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.application.VoidLossUseCase;
import net.enthusia.loreitems.paper.AdoptHeldItemCommandExecutor;
import net.enthusia.loreitems.paper.CreateDefinitionCommandExecutor;
import net.enthusia.loreitems.paper.GiveLoreItemCommandExecutor;
import net.enthusia.loreitems.paper.LoreItemsAdministrationCommandExecutor;
import net.enthusia.loreitems.paper.LoreItemsCommandExecutor;
import net.enthusia.loreitems.paper.PaperAnomalyWarningWorker;
import net.enthusia.loreitems.paper.PaperDirectDeliveryOperator;
import net.enthusia.loreitems.paper.PaperDirectDeliveryWorker;
import net.enthusia.loreitems.paper.PaperDisplayItemListener;
import net.enthusia.loreitems.paper.PaperHeldItemAdoptionOperator;
import net.enthusia.loreitems.paper.PaperHeldItemDefinitionSnapshotter;
import net.enthusia.loreitems.paper.PaperIdentityAnomalyListener;
import net.enthusia.loreitems.paper.PaperMutationRecoveryWorker;
import net.enthusia.loreitems.paper.PaperTrackedItemProtectionListener;
import net.enthusia.loreitems.sqlite.BoundedDatabaseExecutor;
import net.enthusia.loreitems.sqlite.MigrationRunner;
import net.enthusia.loreitems.sqlite.SQLiteAnomalyRepository;
import net.enthusia.loreitems.sqlite.SQLiteAuditRepository;
import net.enthusia.loreitems.sqlite.SQLiteConnectionFactory;
import net.enthusia.loreitems.sqlite.SQLiteCurrentStateRepository;
import net.enthusia.loreitems.sqlite.SQLiteDirectDeliveryRepository;
import net.enthusia.loreitems.sqlite.SQLiteDisplayItemObservationStore;
import net.enthusia.loreitems.sqlite.SQLiteHeldItemAdoptionStore;
import net.enthusia.loreitems.sqlite.SQLiteItemAnomalyObservationStore;
import net.enthusia.loreitems.sqlite.SQLiteObservationRepository;
import net.enthusia.loreitems.sqlite.SQLitePendingMutationRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import net.enthusia.loreitems.sqlite.SQLiteUnitOfWork;
import net.enthusia.loreitems.sqlite.SQLiteVoidLossStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

// Paper plugins may own bounded lifecycle executors; this is not a J2EE web application.
@SuppressWarnings("PMD.DoNotUseThreads")
public final class LoreItemsPlugin extends JavaPlugin {
    private static final String STOPPING_RELOAD_DETAIL =
            "The plugin is stopping; configuration reload was not applied.";
    private static final int ANOMALY_WARNING_INTERVAL_SECONDS = 300;

    private final AtomicReference<LoreItemsServiceV1> serviceDelegate =
            new AtomicReference<>(new UnavailableService("Foundation storage has not started."));
    private final AtomicReference<AtomicConfiguration> configuration =
            new AtomicReference<>(new AtomicConfiguration(FoundationConfiguration.defaults()));
    private final AtomicReference<CreateDefinitionUseCase> createDefinitionDelegate =
            new AtomicReference<>(unavailableCreateDefinitionUseCase());
    private final AtomicReference<AdoptHeldItemUseCase> adoptHeldItemDelegate =
            new AtomicReference<>(unavailableAdoptHeldItemUseCase());
    private final AtomicReference<VoidLossUseCase> voidLossDelegate =
            new AtomicReference<>(unavailableVoidLossUseCase());
    private final AtomicReference<DisplayItemObservationUseCase> displayObservationDelegate =
            new AtomicReference<>(unavailableDisplayItemObservationUseCase());
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
    private volatile PaperDirectDeliveryWorker directDeliveryWorker;
    private volatile PaperMutationRecoveryWorker mutationRecoveryWorker;
    private volatile PaperTrackedItemProtectionListener protectionListener;
    private volatile PaperDisplayItemListener displayItemListener;
    private volatile PaperIdentityAnomalyListener identityAnomalyListener;
    private volatile PaperAnomalyWarningWorker anomalyWarningWorker;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        registerCommands();
        if (!activateProtectionListeners()) {
            return;
        }
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
        CreateDefinitionCommandExecutor createExecutor = new CreateDefinitionCommandExecutor(
                this,
                registeredCreateDefinitionUseCase,
                new PaperHeldItemDefinitionSnapshotter());
        AdoptHeldItemCommandExecutor adoptExecutor = new AdoptHeldItemCommandExecutor(
                this,
                adoptHeldItemDelegate::get,
                new PaperHeldItemAdoptionOperator());
        GiveLoreItemCommandExecutor giveExecutor = new GiveLoreItemCommandExecutor(
                this,
                registeredService,
                this::wakeDirectDeliveries);
        LoreItemsAdministrationCommandExecutor administrationExecutor =
                new LoreItemsAdministrationCommandExecutor(
                        this,
                        () -> configuration.get().current().defaultPageSize());
        command.setExecutor(new LoreItemsCommandExecutor(
                createExecutor,
                adoptExecutor,
                giveExecutor,
                administrationExecutor));
    }

    private boolean activateProtectionListeners() {
        PaperTrackedItemProtectionListener protection = null;
        PaperDisplayItemListener display = null;
        try {
            protection = new PaperTrackedItemProtectionListener(
                    this,
                    voidLossDelegate::get,
                    () -> configuration.get().current().mutationBudgetPerTick());
            display = new PaperDisplayItemListener(
                    this,
                    displayObservationDelegate::get,
                    () -> configuration.get().current().mutationBudgetPerTick());
            protection.start();
            display.start();
            protectionListener = protection;
            displayItemListener = display;
            return true;
        } catch (RuntimeException exception) {
            closeQuietly(display, "display-item listener");
            closeQuietly(protection, "tracked-item protection listener");
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not start tracked-item protection listeners; disabling LoreItems.",
                    exception);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    @Override
    public void onDisable() {
        synchronized (lifecycleLock) {
            stopping = true;
            serviceDelegate.set(new UnavailableService("The plugin is stopping."));
            createDefinitionDelegate.set(unavailableCreateDefinitionUseCase());
            adoptHeldItemDelegate.set(unavailableAdoptHeldItemUseCase());
            voidLossDelegate.set(unavailableVoidLossUseCase());
            displayObservationDelegate.set(unavailableDisplayItemObservationUseCase());
        }
        closeQuietly(identityAnomalyListener, "identity-anomaly listener");
        closeQuietly(anomalyWarningWorker, "anomaly-warning worker");
        closeQuietly(displayItemListener, "display-item listener");
        closeQuietly(protectionListener, "tracked-item protection listener");
        closeQuietly(directDeliveryWorker, "direct-delivery worker");
        closeQuietly(mutationRecoveryWorker, "mutation-recovery worker");
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
        SQLitePendingMutationRepository mutationRepository =
                new SQLitePendingMutationRepository(runtime);
        recoverExpiredClaims(repository, loaded.deliveryClaimBatchSize());
        recoverExpiredMutationClaims(
                mutationRepository, loaded.deliveryClaimBatchSize());
        Clock clock = Clock.systemUTC();
        LoreItemsServiceV1 deliveryService = new FoundationLoreItemsService(
                new PersistingExternalDeliveryUseCase(repository, clock));
        DirectDeliveryExecutionUseCase directDeliveryUseCase =
                new PersistingDirectDeliveryExecutionUseCase(
                        repository,
                        clock,
                        Duration.ofSeconds(loaded.deliveryClaimLeaseSeconds()));
        CreateDefinitionUseCase createDefinitionUseCase = new PersistingCreateDefinitionUseCase(
                new SQLiteUnitOfWork(runtime), clock);
        AdoptHeldItemUseCase adoptHeldItemUseCase = new PersistingAdoptHeldItemUseCase(
                new SQLiteHeldItemAdoptionStore(runtime),
                clock,
                Duration.ofSeconds(loaded.deliveryClaimLeaseSeconds()));
        VoidLossUseCase voidLossUseCase = new PersistingVoidLossUseCase(
                new SQLiteVoidLossStore(runtime),
                clock,
                Duration.ofSeconds(loaded.deliveryClaimLeaseSeconds()));
        DisplayItemObservationUseCase displayObservationUseCase =
                new PersistingDisplayItemObservationUseCase(
                        new SQLiteDisplayItemObservationStore(runtime),
                        clock);
        SQLiteAnomalyRepository anomalyRepository = new SQLiteAnomalyRepository(runtime);
        LoreItemsAdministrationUseCase administrationUseCase =
                new PersistingLoreItemsAdministrationUseCase(
                        anomalyRepository,
                        new SQLiteAuditRepository(runtime),
                        new SQLiteCurrentStateRepository(runtime),
                        new SQLiteObservationRepository(runtime),
                        repository,
                        mutationRepository);
        ItemAnomalyObservationUseCase anomalyObservationUseCase =
                new PersistingItemAnomalyObservationUseCase(
                        new SQLiteItemAnomalyObservationStore(runtime),
                        clock);
        if (publishWritableServices(
                deliveryService,
                createDefinitionUseCase,
                adoptHeldItemUseCase,
                voidLossUseCase,
                displayObservationUseCase)) {
            activateDirectDeliveryWorker(directDeliveryUseCase, loaded);
            activateMutationRecoveryWorker(mutationRepository, loaded);
            activateAdministrationServices(
                    administrationUseCase,
                    anomalyObservationUseCase,
                    loaded);
            getLogger().info(
                    "Durable storage is active; definition creation, adoption, protection, "
                            + "display observations, and terminal void loss are available. "
                            + "Delivery, recovery, anomaly, and administration components are "
                            + "activating on the server thread.");
        }
    }

    private void activateDirectDeliveryWorker(
            DirectDeliveryExecutionUseCase useCase,
            FoundationConfiguration loaded) {
        try {
            getServer().getScheduler().runTask(this, () -> {
                synchronized (lifecycleLock) {
                    if (stopping || directDeliveryWorker != null) {
                        return;
                    }
                    PaperDirectDeliveryWorker worker = new PaperDirectDeliveryWorker(
                            this,
                            useCase,
                            new PaperDirectDeliveryOperator(),
                            loaded.deliveryClaimBatchSize(),
                            loaded.mutationBudgetPerTick());
                    try {
                        worker.start();
                        directDeliveryWorker = worker;
                        getLogger().info("Queued direct-delivery processing is active.");
                    } catch (RuntimeException exception) {
                        closeQuietly(worker, "direct-delivery worker");
                        getLogger().log(
                                java.util.logging.Level.SEVERE,
                                "Could not start the direct-delivery worker; disabling LoreItems.",
                                exception);
                        getServer().getPluginManager().disablePlugin(this);
                    }
                }
            });
        } catch (RuntimeException exception) {
            publishUnavailableServices(
                    "Direct-delivery activation could not be scheduled.");
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not activate the direct-delivery worker; writes remain unavailable.",
                    exception);
        }
    }

    private void activateMutationRecoveryWorker(
            SQLitePendingMutationRepository repository,
            FoundationConfiguration loaded) {
        try {
            getServer().getScheduler().runTask(this, () -> {
                synchronized (lifecycleLock) {
                    if (stopping || mutationRecoveryWorker != null) {
                        return;
                    }
                    PaperMutationRecoveryWorker worker =
                            new PaperMutationRecoveryWorker(
                                    this,
                                    repository,
                                    Math.min(
                                            loaded.deliveryClaimBatchSize(),
                                            loaded.mutationBudgetPerTick()));
                    try {
                        worker.start();
                        mutationRecoveryWorker = worker;
                        getLogger().info("Expired item-mutation recovery is active.");
                    } catch (RuntimeException exception) {
                        closeQuietly(worker, "mutation-recovery worker");
                        getLogger().log(
                                java.util.logging.Level.SEVERE,
                                "Could not start expired mutation recovery; disabling LoreItems.",
                                exception);
                        getServer().getPluginManager().disablePlugin(this);
                    }
                }
            });
        } catch (RuntimeException exception) {
            publishUnavailableServices(
                    "Expired mutation recovery activation could not be scheduled.");
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not activate expired mutation recovery; writes remain unavailable.",
                    exception);
        }
    }

    private void activateAdministrationServices(
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            FoundationConfiguration loaded) {
        try {
            getServer().getScheduler().runTask(
                    this,
                    () -> activateAdministrationServicesOnMainThread(
                            administrationUseCase,
                            anomalyObservationUseCase,
                            loaded));
        } catch (RuntimeException exception) {
            publishUnavailableServices(
                    "Lore-item anomaly and administration activation could not be scheduled.");
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not schedule lore-item anomaly and administration services; "
                            + "writes remain unavailable.",
                    exception);
        }
    }

    private void activateAdministrationServicesOnMainThread(
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            FoundationConfiguration loaded) {
        synchronized (lifecycleLock) {
            if (stopping || identityAnomalyListener != null) {
                return;
            }
            startAdministrationComponents(
                    administrationUseCase,
                    anomalyObservationUseCase,
                    loaded);
        }
    }

    private void startAdministrationComponents(
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            FoundationConfiguration loaded) {
        PaperAnomalyWarningWorker warningWorker = new PaperAnomalyWarningWorker(
                this,
                administrationUseCase,
                ANOMALY_WARNING_INTERVAL_SECONDS,
                loaded.defaultPageSize());
        PaperIdentityAnomalyListener anomalyListener = new PaperIdentityAnomalyListener(
                this,
                loaded.mutationBudgetPerTick());
        ServicesManager services = getServer().getServicesManager();
        try {
            registerAdministrationServices(
                    services,
                    administrationUseCase,
                    anomalyObservationUseCase,
                    warningWorker);
            warningWorker.start();
            anomalyListener.start();
            anomalyWarningWorker = warningWorker;
            identityAnomalyListener = anomalyListener;
            getLogger().info(
                    "Lore-item anomaly detection, warnings, and administration are active.");
        } catch (RuntimeException exception) {
            rollbackAdministrationServices(
                    services,
                    administrationUseCase,
                    anomalyObservationUseCase,
                    warningWorker,
                    anomalyListener);
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not activate lore-item anomaly and administration services; "
                            + "disabling LoreItems.",
                    exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerAdministrationServices(
            ServicesManager services,
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            PaperAnomalyWarningWorker warningWorker) {
        services.register(
                LoreItemsAdministrationUseCase.class,
                administrationUseCase,
                this,
                ServicePriority.Normal);
        services.register(
                ItemAnomalyObservationUseCase.class,
                anomalyObservationUseCase,
                this,
                ServicePriority.Normal);
        services.register(
                AnomalyWarningSink.class,
                warningWorker,
                this,
                ServicePriority.Normal);
    }

    private void rollbackAdministrationServices(
            ServicesManager services,
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            PaperAnomalyWarningWorker warningWorker,
            PaperIdentityAnomalyListener anomalyListener) {
        closeQuietly(anomalyListener, "identity-anomaly listener");
        closeQuietly(warningWorker, "anomaly-warning worker");
        services.unregister(AnomalyWarningSink.class, warningWorker);
        services.unregister(ItemAnomalyObservationUseCase.class, anomalyObservationUseCase);
        services.unregister(LoreItemsAdministrationUseCase.class, administrationUseCase);
    }

    private void wakeDirectDeliveries(UUID playerId) {
        PaperDirectDeliveryWorker worker = directDeliveryWorker;
        if (worker != null) {
            worker.wakePlayer(playerId);
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
            CreateDefinitionUseCase createDefinitionUseCase,
            AdoptHeldItemUseCase adoptHeldItemUseCase,
            VoidLossUseCase voidLossUseCase,
            DisplayItemObservationUseCase displayObservationUseCase) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(createDefinitionUseCase, "createDefinitionUseCase");
        Objects.requireNonNull(adoptHeldItemUseCase, "adoptHeldItemUseCase");
        Objects.requireNonNull(voidLossUseCase, "voidLossUseCase");
        Objects.requireNonNull(displayObservationUseCase, "displayObservationUseCase");
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            serviceDelegate.set(service);
            createDefinitionDelegate.set(createDefinitionUseCase);
            adoptHeldItemDelegate.set(adoptHeldItemUseCase);
            voidLossDelegate.set(voidLossUseCase);
            displayObservationDelegate.set(displayObservationUseCase);
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
            adoptHeldItemDelegate.set(unavailableAdoptHeldItemUseCase());
            voidLossDelegate.set(unavailableVoidLossUseCase());
            displayObservationDelegate.set(unavailableDisplayItemObservationUseCase());
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

    private void recoverExpiredMutationClaims(
            SQLitePendingMutationRepository repository, int recoveryLimit) {
        int recovered = repository.moveExpiredClaimsToReview(Instant.now(), recoveryLimit)
                .toCompletableFuture()
                .join();
        if (recovered > 0) {
            getLogger().warning(
                    "Moved " + recovered + " expired item-mutation claims to REVIEW_REQUIRED.");
        }
        if (recovered == recoveryLimit) {
            getLogger().warning(
                    "The bounded startup mutation-recovery batch was full; additional expired "
                            + "claims remain for bounded periodic recovery.");
        }
    }

    private void handleInitializationFailure(Exception exception) {
        publishUnavailableServices(
                "Foundation initialization failed: " + safeMessage(exception));
        getLogger().log(
                java.util.logging.Level.SEVERE,
                "LoreItems foundation initialization failed; writes remain unavailable.",
                exception);
    }

    private void closeQuietly(AutoCloseable component, String name) {
        if (component == null) {
            return;
        }
        try {
            component.close();
        } catch (Exception exception) {
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not close the LoreItems " + name + '.',
                    exception);
        }
    }

    private static CreateDefinitionUseCase unavailableCreateDefinitionUseCase() {
        return request -> CompletableFuture.completedFuture(
                CreateDefinitionResult.serviceUnavailable());
    }

    private static AdoptHeldItemUseCase unavailableAdoptHeldItemUseCase() {
        return new AdoptHeldItemUseCase() {
            @Override
            public CompletionStage<PrepareHeldItemAdoptionResult> prepare(
                    PrepareHeldItemAdoptionRequest request) {
                return CompletableFuture.completedFuture(
                        PrepareHeldItemAdoptionResult.serviceUnavailable());
            }

            @Override
            public CompletionStage<Boolean> complete(
                    PreparedHeldItemAdoption adoption,
                    String afterFingerprint) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Boolean> requireReview(
                    PreparedHeldItemAdoption adoption,
                    String reason) {
                return CompletableFuture.completedFuture(false);
            }
        };
    }

    private static VoidLossUseCase unavailableVoidLossUseCase() {
        return new VoidLossUseCase() {
            @Override
            public CompletionStage<PrepareResult> prepare(Request request) {
                return CompletableFuture.completedFuture(PrepareResult.of(
                        PrepareStatus.SERVICE_UNAVAILABLE,
                        "Durable storage is unavailable; the item remains protected."));
            }

            @Override
            public CompletionStage<Boolean> complete(PreparedVoidLoss loss) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Boolean> abort(PreparedVoidLoss loss, String reason) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Boolean> requireReview(
                    PreparedVoidLoss loss,
                    String reason) {
                return CompletableFuture.completedFuture(false);
            }
        };
    }

    private static DisplayItemObservationUseCase unavailableDisplayItemObservationUseCase() {
        return request -> CompletableFuture.completedFuture(
                DisplayItemObservationUseCase.Result.of(
                        DisplayItemObservationUseCase.Status.SERVICE_UNAVAILABLE,
                        "Durable storage is unavailable; display evidence was not changed."));
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
