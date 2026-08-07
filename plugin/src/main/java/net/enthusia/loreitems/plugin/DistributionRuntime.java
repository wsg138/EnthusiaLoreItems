package net.enthusia.loreitems.plugin;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.BindDistributionRecipientsUseCase;
import net.enthusia.loreitems.application.DistributionCampaignAdministrationUseCase;
import net.enthusia.loreitems.application.DistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.PersistingDistributionCampaignAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingDistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.paper.DistributionCampaignCommandExecutor;
import net.enthusia.loreitems.paper.PaperCachedPlayerIdentityResolver;
import net.enthusia.loreitems.paper.PaperDirectDeliveryOperator;
import net.enthusia.loreitems.paper.PaperDistributionCampaignCoordinator;
import net.enthusia.loreitems.paper.PaperDistributionDeliveryWorker;
import net.enthusia.loreitems.paper.PaperDistributionMarkerReconciler;
import net.enthusia.loreitems.paper.PaperDistributionMarkerRecoveryWorker;
import net.enthusia.loreitems.paper.PaperDistributionRecipientBindingWorker;
import net.enthusia.loreitems.paper.PaperGroupFileCatalog;
import net.enthusia.loreitems.sqlite.SQLiteAuditRepository;
import net.enthusia.loreitems.sqlite.SQLiteCancellableDistributionDeliveryRepository;
import net.enthusia.loreitems.sqlite.SQLiteDefinitionRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionCampaignRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionCampaignStartRepository;
import net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns all WP-03 campaign runtime components and their bounded lifecycle. */
final class DistributionRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperGroupFileCatalog groupCatalog;
    private final PaperDistributionDeliveryWorker deliveryWorker;
    private final PaperDistributionRecipientBindingWorker bindingWorker;
    private final PaperDistributionMarkerRecoveryWorker markerWorker;
    private final DistributionCampaignCommandExecutor commandExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile boolean started;

    DistributionRuntime(
            JavaPlugin plugin,
            SQLiteStorageRuntime storage,
            FoundationConfiguration configuration,
            Executor blockingExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(configuration, "configuration");
        Executor workerExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");

        Clock clock = Clock.systemUTC();
        groupCatalog = new PaperGroupFileCatalog(plugin.getDataFolder().toPath());
        SQLiteDistributionCampaignRepository campaigns =
                new SQLiteDistributionCampaignRepository(storage);
        SQLiteDistributionRecipientRepository recipients =
                new SQLiteDistributionRecipientRepository(storage);
        DistributionCampaignAdministrationUseCase administration =
                new PersistingDistributionCampaignAdministrationUseCase(
                        campaigns,
                        recipients,
                        new SQLiteAuditRepository(storage),
                        clock);
        PaperDistributionMarkerReconciler markerReconciler =
                new PaperDistributionMarkerReconciler(groupCatalog, campaigns, workerExecutor);
        DistributionDeliveryExecutionUseCase delivery =
                new PersistingDistributionDeliveryExecutionUseCase(
                        new SQLiteCancellableDistributionDeliveryRepository(storage),
                        clock,
                        Duration.ofSeconds(configuration.deliveryClaimLeaseSeconds()));
        deliveryWorker = new PaperDistributionDeliveryWorker(
                plugin,
                delivery,
                new PaperDirectDeliveryOperator(),
                configuration.deliveryClaimBatchSize(),
                configuration.mutationBudgetPerTick());
        BindDistributionRecipientsUseCase binder =
                new BindDistributionRecipientsUseCase(recipients);
        bindingWorker = new PaperDistributionRecipientBindingWorker(
                plugin,
                binder,
                deliveryWorker::wakePlayer,
                configuration.defaultPageSize(),
                configuration.mutationBudgetPerTick());
        markerWorker = new PaperDistributionMarkerRecoveryWorker(
                plugin, markerReconciler, configuration.defaultPageSize());
        PaperDistributionCampaignCoordinator coordinator =
                new PaperDistributionCampaignCoordinator(
                        groupCatalog,
                        new SQLiteDefinitionRepository(storage),
                        new SQLiteDistributionCampaignStartRepository(storage),
                        new PaperCachedPlayerIdentityResolver(),
                        workerExecutor,
                        workerExecutor);
        commandExecutor = new DistributionCampaignCommandExecutor(
                plugin,
                groupCatalog,
                coordinator,
                administration,
                markerReconciler,
                deliveryWorker,
                markerWorker::wake,
                workerExecutor,
                configuration.defaultPageSize());
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(markerWorker, "distribution marker worker");
        closeQuietly(bindingWorker, "distribution identity-binding worker");
        closeQuietly(deliveryWorker, "distribution delivery worker");
        closeQuietly(commandExecutor, "distribution command executor");
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
