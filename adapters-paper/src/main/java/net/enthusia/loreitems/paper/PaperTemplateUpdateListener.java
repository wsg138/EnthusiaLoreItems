package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Lifecycle owner for naturally accessible inventory-backed template updates. */
final class PaperTemplateUpdateListener implements AutoCloseable {
    private final Plugin plugin;
    private final PaperTemplateUpdateAccessController controller;
    private final PaperTemplateUpdateEvents events;

    private BukkitTask scanTask;
    private boolean closed;

    PaperTemplateUpdateListener(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            int budget) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.controller = new PaperTemplateUpdateAccessController(
                plugin,
                Objects.requireNonNull(useCase, "useCase"),
                Objects.requireNonNull(operator, "operator"),
                budget);
        this.events = new PaperTemplateUpdateEvents(controller);
    }

    PaperTemplateUpdateListener(
            Plugin plugin,
            PaperTemplateUpdateCoordinator coordinator,
            PaperTemplateUpdateAccessRegistry accessRegistry,
            int budget) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.controller = new PaperTemplateUpdateAccessController(
                plugin,
                Objects.requireNonNull(coordinator, "coordinator"),
                Objects.requireNonNull(accessRegistry, "accessRegistry"),
                budget);
        this.events = new PaperTemplateUpdateEvents(controller);
    }

    void start() {
        if (closed || scanTask != null) {
            throw new IllegalStateException("Template-update listener cannot be started");
        }
        plugin.getServer().getPluginManager().registerEvents(events, plugin);
        plugin.getServer().getOnlinePlayers().forEach(controller::enqueuePlayer);
        try {
            scanTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, controller::drain, 1L, 1L);
        } catch (RuntimeException exception) {
            HandlerList.unregisterAll(events);
            throw exception;
        }
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(events);
        BukkitTask task = scanTask;
        if (task != null) {
            task.cancel();
        }
        controller.close();
    }
}
