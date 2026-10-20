package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Startup-only loaded chunk keys; discovery is paced and never force-loads chunks. */
final class PaperStartupChunkDiscovery {
    private final Plugin plugin;
    private final ArrayDeque<PaperTrackingScanRequest.ChunkReference> pending =
            new ArrayDeque<>();

    PaperStartupChunkDiscovery(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void capture() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                pending.addLast(PaperTrackingScanRequest.ChunkReference.capture(chunk));
            }
        }
    }

    void seed(int budget, BiConsumer<PaperTrackingScanRequest.ChunkReference, String> observer) {
        Objects.requireNonNull(observer, "observer");
        for (int n = 0; n < budget && !pending.isEmpty(); n++) {
            PaperTrackingScanRequest.ChunkReference key = pending.removeFirst();
            World world = plugin.getServer().getWorld(key.worldId());
            if (world != null && world.isChunkLoaded(key.x(), key.z())) {
                observer.accept(key, "startup-loaded-chunk");
            }
        }
    }

    void clear() {
        pending.clear();
    }
}
