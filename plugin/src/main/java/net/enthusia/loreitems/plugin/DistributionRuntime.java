package net.enthusia.loreitems.plugin;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.BindDistributionRecipientsUseCase;
import net.enthusia.loreitems.application.DistributionCampaignAdministrationUseCase;
import net.enthusia.loreitems.application.DistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.MetricsDistributionCampaignAdministrationUseCase;
import net.enthusia.loreitems.application.MetricsDistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PersistingDistributionCampaignAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingDistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.paper.DistributionCampaignCommandDependencies;
import net.enthusia.loreitems.paper.DistributionCampaignCommandExecutor;
import net.enthusia.loreitems.paper.PaperCachedPlayerIdentityResolver;
import net.enthusia.loreitems.paper.PaperDirectDeliveryOperator;
import net.enthusia.loreitems.paper.PaperDistributionCampaignCoordinator;
import net.enthusia.loreitems.paper.PaperDistributionDeliveryWorker;
import net.enthusia.loreitems.paper.PaperDistributionMarkerReconciler;
import net.enthusia.loreitems.paper.PaperDistributionMarkerRecoveryWorker;
import net.enthusia.loreitems.paper.PaperDistributionRecipientBindingWorker;
import net.enthusia.loreitems.paper.PaperGroupFileCatalog;
import net.enthusia.loreitems.sqlite.SQLiteCancellableDistributionDeliveryRepository;
import net.enthusia.loreitems.sqlite.SQLiteDefinitionRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionCampaignControlRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionCampaignRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionCampaignStartRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionReviewRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns all WP-03 campaign runtime components and their bounded lifecycle. */
@SuppressWarnings("PMD.DoNotUseThreads")
final class DistributionRuntime implements AutoCloseable {
    private static final int DISTRIBUTION_QUEUE_CAPACITY = 64;

    private final JavaPlugin plugin;
    private final PaperGroupFileCatalog groupCatalog;
    private final DistributionCampaignAdministrationUseCase administration;
    private final PaperDistributionDeliveryWorker deliveryWorker;
    private final PaperDistributionRecipientBindingWorker bindingWorker;
    private final PaperDistributionMarkerRecoveryWorker markerWorker;
    private final DistributionCampaignCommandExecutor commandExecutor;
    private final ThreadPoolExecutor distributionExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile boolean serviceRegistered;
    private volatile boolean started;

    DistributionRuntime(
            JavaPlugin plugin,
            SQLiteStorageRuntime storage,
            FoundationConfiguration configuration,
            Executor blockingExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        SQLiteStorageRuntime requiredStorage = Objects.requireNonNull(storage, "storage");
        FoundationConfiguration requiredConfiguration =
                Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        distributionExecutor = createDistributionExecutor();
        Executor workerExecutor = distributionExecutor;
        MetricsPort metrics = requiredStorage.metrics();
        Clock clock = Clock.systemUTC();

        groupCatalog = new PaperGroupFileCatalog(plugin.getDataFolder().toPath());
        SQLiteDistributionCampaignRepository campaigns =
                new SQLiteDistributionCampaignRepository(requiredStorage);
        SQLiteDistributionRecipientRepository recipients =
                new SQLiteDistributionRecipientRepository(requiredStorage);
        administration = buildAdministration(requiredStorage, campaigns, recipients, metrics, clock);
        PaperDistributionMarkerReconciler markerReconciler =
                new PaperDistributionMarkerReconciler(groupCatalog, campaigns, workerExecutor);
        deliveryWorker = buildDeliveryWorker(
                plugin, requiredStorage, requiredConfiguration, metrics, clock);
        bindingWorker = buildBindingWorker(
                plugin, recipients, deliveryWorker, requiredConfiguration);
        markerWorker = new PaperDistributionMarkerRecoveryWorker(
                plugin, markerReconciler, requiredConfiguration.defaultPageSize());
        commandExecutor = buildCommandExecutor(
                requiredStorage, requiredConfiguration, workerExecutor, markerReconciler);
    }

    private DistributionCampaignAdministrationUseCase buildAdministration(
            SQLiteStorageRuntime storage,
            SQLiteDistributionCampaignRepository campaigns,
            SQLiteDistributionRecipientRepository recipients,
            MetricsPort metrics,
            Clock clock) {
        DistributionCampaignAdministrationUseCase persisted =
                new PersistingDistributionCampaignAdministrationUseCase(
                        campaigns,
                        recipients,
                        new SQLiteDistributionReviewRepository(storage),
                        new SQLiteDistributionCampaignControlRepository(storage),
                        clock);
        return new MetricsDistributionCampaignAdministrationUseCase(persisted, metrics);
    }

    private static PaperDistributionDeliveryWorker buildDeliveryWorker(
            JavaPlugin plugin,
            SQLiteStorageRuntime storage,
            FoundationConfiguration configuration,
            MetricsPort metrics,
            Clock clock) {
        DistributionDeliveryExecutionUseCase persisted =
                new PersistingDistributionDeliveryExecutionUseCase(
                        new SQLiteCancellableDistributionDeliveryRepository(storage),
                        clock,
                        Duration.ofSeconds(configuration.deliveryClaimLeaseSeconds()));
        DistributionDeliveryExecutionUseCase delivery =
                new MetricsDistributionDeliveryExecutionUseCase(persisted, metrics);
        return new PaperDistributionDeliveryWorker(
                plugin,
                delivery,
                new PaperDirectDeliveryOperator(),
                configuration.deliveryClaimBatchSize(),
                configuration.mutationBudgetPerTick());
    }

    private static PaperDistributionRecipientBindingWorker buildBindingWorker(
            JavaPlugin plugin,
            SQLiteDistributionRecipientRepository recipients,
            PaperDistributionDeliveryWorker deliveryWorker,
            FoundationConfiguration configuration) {
        return new PaperDistributionRecipientBindingWorker(
                plugin,
                new BindDistributionRecipientsUseCase(recipients),
                deliveryWorker::wakePlayer,
                configuration.defaultPageSize(),
                configuration.mutationBudgetPerTick());
    }

    private DistributionCampaignCommandExecutor buildCommandExecutor(
            SQLiteStorageRuntime storage,
            FoundationConfiguration configuration,
            Executor workerExecutor,
            PaperDistributionMarkerReconciler markerReconciler) {
        PaperDistributionCampaignCoordinator coordinator = new PaperDistributionCampaignCoordinator(
                groupCatalog,
                new SQLiteDefinitionRepository(storage),
                new SQLiteDistributionCampaignStartRepository(storage),
                new PaperCachedPlayerIdentityResolver(),
                this::scheduleOnMainThread,
                workerExecutor);
        DistributionCampaignCommandDependencies dependencies =
                new DistributionCampaignCommandDependencies(
                        groupCatalog,
                        coordinator,
                        administration,
                        markerReconciler,
                        deliveryWorker,
                        markerWorker::wake,
                        workerExecutor);
        return new DistributionCampaignCommandExecutor(
                plugin, dependencies, configuration.defaultPageSize());
    }

    void activate() throws IOException {
        if (closed.get()) {
            return;
        }
        groupCatalog.initializeDirectories();
        executeOnMain(this::startOnMainThread);
    }

    private void startOnMainThread() {
        requirePrimaryThread();
        if (closed.get() || started) {
            return;
        }
        PluginCommand command = Objects.requireNonNull(
                plugin.getCommand("loredistribution"),
                "plugin.yml must declare the loredistribution command");
        try {
            registerAdministrationService();
            command.setExecutor(commandExecutor);
            command.setTabCompleter(commandExecutor);
            deliveryWorker.start();
            bindingWorker.start();
            markerWorker.start();
            started = true;
            plugin.getLogger().info(
                    "Mass distribution delivery, identity binding, marker recovery, and commands are active.");
        } catch (RuntimeException exception) {
            close();
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not activate the mass distribution runtime; disabling LoreItems.",
                    exception);
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        }
    }

    private void registerAdministrationService() {
        plugin.getServer().getServicesManager().register(
                DistributionCampaignAdministrationUseCase.class,
                administration,
                plugin,
                ServicePriority.Normal);
        serviceRegistered = true;
    }

    private void executeOnMain(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (closed.get()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private void scheduleOnMainThread(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (closed.get()) {
            throw new RejectedExecutionException("Distribution runtime is closed");
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            throw new RejectedExecutionException(
                    "Could not schedule distribution work on the server thread", exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (!started && !serviceRegistered) {
            closeQuietly(commandExecutor, "distribution command executor");
            distributionExecutor.shutdownNow();
            return;
        }
        unregisterAdministrationService();
        closeQuietly(markerWorker, "distribution marker worker");
        closeQuietly(bindingWorker, "distribution identity-binding worker");
        closeQuietly(deliveryWorker, "distribution delivery worker");
        closeQuietly(commandExecutor, "distribution command executor");
        distributionExecutor.shutdownNow();
    }

    boolean isTerminated() {
        return distributionExecutor.isTerminated();
    }

    private static ThreadPoolExecutor createDistributionExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DISTRIBUTION_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "loreitems-distribution");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void unregisterAdministrationService() {
        if (!serviceRegistered) {
            return;
        }
        plugin.getServer().getServicesManager().unregister(
                DistributionCampaignAdministrationUseCase.class, administration);
        serviceRegistered = false;
    }

    private void closeQuietly(AutoCloseable component, String name) {
        try {
            component.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not close " + name + '.', exception);
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Distribution runtime startup must run on the server thread");
        }
    }
}
