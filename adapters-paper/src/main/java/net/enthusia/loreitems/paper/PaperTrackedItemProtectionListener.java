package net.enthusia.loreitems.paper;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class PaperTrackedItemProtectionListener implements Listener, AutoCloseable {
    private static final int MAX_COMPLETION_ATTEMPTS = 3;
    private static final Duration RETRY_COOLDOWN = Duration.ofSeconds(5);

    private final Plugin plugin;
    private final Supplier<VoidLossUseCase> useCaseSupplier;
    private final PaperItemIdentityCodec identityCodec;
    private final int maxInFlight;
    private final int maxCooldowns;
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retryAfterNanos = new ConcurrentHashMap<>();

    private volatile boolean closed;

    public PaperTrackedItemProtectionListener(
            Plugin plugin,
            Supplier<VoidLossUseCase> useCaseSupplier,
            int maxInFlight) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.identityCodec = new PaperItemIdentityCodec();
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.maxCooldowns = Math.multiplyExact(maxInFlight, 4);
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Protection listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (hasLoreIdentityEvidence(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item
                && hasLoreIdentityEvidence(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (hasLoreIdentityEvidence(event.getEntity().getItemStack())
                || hasLoreIdentityEvidence(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        ItemIdentityReadResult identity = identityCodec.readIdentity(item.getItemStack());
        if (identity instanceof ItemIdentityReadResult.Untracked) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                && identity instanceof ItemIdentityReadResult.Tracked tracked) {
            beginVoidLoss(item, tracked.identity());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDurabilityDamage(PlayerItemDamageEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    private boolean hasLoreIdentityEvidence(ItemStack item) {
        return !(identityCodec.readIdentity(item) instanceof ItemIdentityReadResult.Untracked);
    }

    private void beginVoidLoss(Item item, LoreItemIdentity identity) {
        UUID instanceId = identity.instanceId().value();
        if (closed || !tryBegin(instanceId)) {
            return;
        }
        VoidLossUseCase.Request request = new VoidLossUseCase.Request(
                identity,
                item.getUniqueId(),
                locationKey(item));
        useCaseSupplier.get().prepare(request).whenComplete((result, failure) -> {
            if (failure != null) {
                logFailure("Could not prepare terminal void loss", failure);
                finish(instanceId, true);
                return;
            }
            if (result.status() != VoidLossUseCase.PrepareStatus.PREPARED) {
                finish(instanceId, true);
                return;
            }
            runOnServerThread(() -> applyPreparedLoss(result.prepared()));
        });
    }

    private boolean tryBegin(UUID instanceId) {
        Long retryAt = retryAfterNanos.get(instanceId);
        long now = System.nanoTime();
        if (retryAt != null) {
            if (retryAt > now) {
                return false;
            }
            retryAfterNanos.remove(instanceId, retryAt);
        }
        return inFlight.size() < maxInFlight && inFlight.putIfAbsent(instanceId, Boolean.TRUE) == null;
    }

    private void applyPreparedLoss(PreparedVoidLoss loss) {
        if (closed) {
            requireReview(loss, "Plugin stopped before the prepared void removal ran.");
            return;
        }
        Entity entity = plugin.getServer().getEntity(loss.entityId());
        if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
            requireReview(loss, "The prepared item entity was unavailable before removal.");
            return;
        }
        ItemIdentityReadResult observed = identityCodec.readIdentity(item.getItemStack());
        if (!(observed instanceof ItemIdentityReadResult.Tracked tracked)
                || !tracked.identity().equals(loss.identity())) {
            requireReview(loss, "The item identity changed after void loss was prepared.");
            return;
        }
        if (item.getLocation().getY() >= item.getWorld().getMinHeight()) {
            abort(loss, "The item was no longer below the world's minimum height.");
            return;
        }
        item.remove();
        complete(loss, 1);
    }

    private void complete(PreparedVoidLoss loss, int attempt) {
        useCaseSupplier.get().complete(loss).whenComplete((completed, failure) -> {
            if (failure == null && Boolean.TRUE.equals(completed)) {
                finish(loss.identity().instanceId().value(), false);
                return;
            }
            if (attempt < MAX_COMPLETION_ATTEMPTS && !closed) {
                complete(loss, attempt + 1);
                return;
            }
            if (failure != null) {
                logFailure("Could not complete terminal void loss", failure);
            }
            requireReview(loss, "The item entity was removed but durable completion was not confirmed.");
        });
    }

    private void abort(PreparedVoidLoss loss, String reason) {
        useCaseSupplier.get().abort(loss, reason).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logFailure("Could not abort prepared void loss", failure);
            }
            finish(loss.identity().instanceId().value(), true);
        });
    }

    private void requireReview(PreparedVoidLoss loss, String reason) {
        useCaseSupplier.get().requireReview(loss, reason).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logFailure("Could not mark void loss for review", failure);
            }
            finish(loss.identity().instanceId().value(), true);
        });
    }

    private void finish(UUID instanceId, boolean cooldown) {
        inFlight.remove(instanceId);
        if (cooldown && retryAfterNanos.size() < maxCooldowns) {
            retryAfterNanos.put(instanceId, System.nanoTime() + RETRY_COOLDOWN.toNanos());
        }
    }

    private void runOnServerThread(Runnable task) {
        if (closed) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (RuntimeException exception) {
            logFailure("Could not schedule prepared void loss", exception);
        }
    }

    private void logFailure(String message, Throwable failure) {
        plugin.getLogger().log(Level.SEVERE, message, failure);
    }

    private static String locationKey(Item item) {
        return item.getWorld().getKey() + ":"
                + item.getLocation().getBlockX() + ":"
                + item.getLocation().getBlockY() + ":"
                + item.getLocation().getBlockZ();
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        retryAfterNanos.clear();
    }
}
