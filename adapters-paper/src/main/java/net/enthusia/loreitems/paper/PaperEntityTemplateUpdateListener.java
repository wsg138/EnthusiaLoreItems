package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Lifecycle owner for bounded template updates on already-loaded item-bearing entities. */
final class PaperEntityTemplateUpdateListener implements AutoCloseable {
    private final Plugin plugin;
    private final PaperEntityTemplateUpdateController controller;
    private final PaperEntityTemplateUpdateEvents events;

    private BukkitTask scanTask;
    private boolean closed;

    PaperEntityTemplateUpdateListener(
            Plugin plugin,
            PaperTemplateUpdateAccessRegistry registry,
            int budget) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.controller = new PaperEntityTemplateUpdateController(
                plugin,
                Objects.requireNonNull(registry, "registry"),
                budget);
        this.events = new PaperEntityTemplateUpdateEvents(controller);
    }

    void start() {
        if (closed || scanTask != null) {
            throw new IllegalStateException("Entity template-update listener cannot be started");
        }
        plugin.getServer().getPluginManager().registerEvents(events, plugin);
        try {
            scanTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, controller::drain, 1L, 1L);
        } catch (RuntimeException exception) {
            HandlerList.unregisterAll(events);
            throw exception;
        }
    }

    void wakeAccessible() {
        controller.topologyChanged();
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
