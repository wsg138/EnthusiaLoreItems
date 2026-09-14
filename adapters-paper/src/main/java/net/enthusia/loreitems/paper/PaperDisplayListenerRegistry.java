package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.plugin.Plugin;

/** Tracks display-observation shutdown barriers without coupling registry state to event handling. */
final class PaperDisplayListenerRegistry {
    private static final Object LOCK = new Object();
    private static final Map<Plugin, Set<CompletableFuture<Void>>> BARRIERS =
            new IdentityHashMap<>();

    private PaperDisplayListenerRegistry() {}

    static void register(Plugin plugin, CompletableFuture<Void> barrier) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(barrier, "barrier");
        synchronized (LOCK) {
            BARRIERS.computeIfAbsent(plugin, ignored -> new HashSet<>()).add(barrier);
        }
        barrier.whenComplete((ignored, failure) -> unregister(plugin, barrier));
    }

    static CompletionStage<Void> quiescenceFor(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        CompletableFuture<?>[] barriers;
        synchronized (LOCK) {
            Set<CompletableFuture<Void>> registered = BARRIERS.get(plugin);
            if (registered == null || registered.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            barriers = registered.toArray(CompletableFuture[]::new);
        }
        return CompletableFuture.allOf(barriers);
    }

    private static void unregister(Plugin plugin, CompletableFuture<Void> barrier) {
        synchronized (LOCK) {
            Set<CompletableFuture<Void>> registered = BARRIERS.get(plugin);
            if (registered == null) {
                return;
            }
            registered.remove(barrier);
            if (registered.isEmpty()) {
                BARRIERS.remove(plugin);
            }
        }
    }
}
