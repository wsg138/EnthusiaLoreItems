package net.enthusia.loreitems.plugin;

import java.util.Objects;
import java.util.logging.Level;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutUseCase;
import net.enthusia.loreitems.paper.PaperAnomalyWarningWorker;
import net.enthusia.loreitems.paper.PaperIdentityAnomalyListener;
import net.enthusia.loreitems.paper.PaperTemplateRevisionPlannerWorker;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns construction and rollback of administration startup components. */
record AdministrationStartupComponents(
        PaperAnomalyWarningWorker warningWorker,
        PaperIdentityAnomalyListener anomalyListener,
        PaperTemplateRevisionPlannerWorker planner,
        JavaPlugin plugin) implements AutoCloseable {

    AdministrationStartupComponents {
        Objects.requireNonNull(warningWorker, "warningWorker");
        Objects.requireNonNull(anomalyListener, "anomalyListener");
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(plugin, "plugin");
    }

    static AdministrationStartupComponents create(
            JavaPlugin plugin,
            LoreItemsAdministrationUseCase administrationUseCase,
            TemplateRevisionRolloutUseCase rolloutUseCase,
            FoundationConfiguration configuration,
            Runnable executionWake) {
        PaperAnomalyWarningWorker warningWorker = null;
        PaperIdentityAnomalyListener anomalyListener = null;
        PaperTemplateRevisionPlannerWorker planner = null;
        try {
            warningWorker = new PaperAnomalyWarningWorker(
                    plugin,
                    administrationUseCase,
                    configuration.duplicateWarningIntervalSeconds(),
                    configuration.defaultPageSize(),
                    configuration.mutationBudgetPerTick());
            anomalyListener = new PaperIdentityAnomalyListener(
                    plugin, configuration.mutationBudgetPerTick());
            planner = new PaperTemplateRevisionPlannerWorker(
                    plugin,
                    rolloutUseCase,
                    configuration.mutationBudgetPerTick(),
                    executionWake);
            return new AdministrationStartupComponents(
                    warningWorker, anomalyListener, planner, plugin);
        } catch (RuntimeException exception) {
            closeQuietly(plugin, planner, "template-revision planner");
            closeQuietly(plugin, anomalyListener, "identity-anomaly listener");
            closeQuietly(plugin, warningWorker, "anomaly-warning worker");
            throw exception;
        }
    }

    @Override
    public void close() {
        closeQuietly(plugin, planner, "template-revision planner");
        closeQuietly(plugin, anomalyListener, "identity-anomaly listener");
        closeQuietly(plugin, warningWorker, "anomaly-warning worker");
    }

    private static void closeQuietly(JavaPlugin plugin, AutoCloseable component, String name) {
        if (component == null) {
            return;
        }
        try {
            component.close();
        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not close the LoreItems " + name + '.',
                    exception);
        }
    }
}
