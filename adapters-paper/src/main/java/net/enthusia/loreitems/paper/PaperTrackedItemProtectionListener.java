package net.enthusia.loreitems.paper;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.destroystokyo.paper.event.player.PlayerReadyArrowEvent;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import io.papermc.paper.event.entity.EntityCompostItemEvent;
import io.papermc.paper.event.entity.EntityDamageItemEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;
import org.bukkit.Material;
import org.bukkit.block.Crafter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class PaperTrackedItemProtectionListener implements Listener, AutoCloseable {
    private static final int MAX_COMPLETION_ATTEMPTS = 3;
    private static final int MIN_IN_FLIGHT = 1;
    private static final int COOLDOWN_CAPACITY_MULTIPLIER = 4;
    private static final Duration RETRY_COOLDOWN = Duration.ofSeconds(5);
    private static final Set<String> CONSUMPTIVE_INTERACTION_MATERIALS = Set.of(
            "BONE_MEAL",
            "BOWL",
            "ENDER_EYE",
            "FIRE_CHARGE",
            "GLASS_BOTTLE",
            "GLOW_INK_SAC",
            "HONEYCOMB",
            "INK_SAC",
            "MAP",
            "OMINOUS_TRIAL_KEY",
            "RESIN_CLUMP",
            "TRIAL_KEY");

    private final Plugin plugin;
    private final Supplier<VoidLossUseCase> useCaseSupplier;
    private final PaperItemIdentityCodec identityCodec;
    private final int maxInFlight;
    private final int maxCooldowns;
    private final Object workflowLock = new Object();
    private final Set<UUID> inFlight = new HashSet<>();
    private final Map<UUID, Long> retryAfterNanos = new HashMap<>();

    private volatile boolean closed;

    public PaperTrackedItemProtectionListener(
            Plugin plugin,
            Supplier<VoidLossUseCase> useCaseSupplier,
            int maxInFlight) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.identityCodec = new PaperItemIdentityCodec();
        if (maxInFlight < MIN_IN_FLIGHT) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.maxCooldowns = Math.multiplyExact(maxInFlight, COOLDOWN_CAPACITY_MULTIPLIER);
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDurabilityDamage(EntityDamageItemEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryResultClick(InventoryClickEvent event) {
        if (event.getSlotType() == InventoryType.SlotType.RESULT
                && containsLoreIdentityEvidence(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (hasLoreIdentityEvidence(event.getResult())) {
            event.setCancelled(true);
            return;
        }
        if (event.getBlock().getState() instanceof Crafter crafter
                && containsLoreIdentityEvidence(crafter.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (hasLoreIdentityEvidence(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceFuel(FurnaceBurnEvent event) {
        if (hasLoreIdentityEvidence(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if (hasLoreIdentityEvidence(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (containsLoreIdentityEvidence(event.getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (event.getDestination().getType() == InventoryType.COMPOSTER
                && hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityCompost(EntityCompostItemEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (hasLoreIdentityEvidence(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        ItemStack source = event.getItemStack();
        if (source != null && hasLoreIdentityEvidence(source)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null
                && hasLoreIdentityEvidence(itemInHand(player, event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlowerPotManipulate(PlayerFlowerPotManipulateEvent event) {
        if (event.isPlacing() && hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protectBucketUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protectBucketUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (hasLoreIdentityEvidence(event.getOriginalBucket())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumptiveInteraction(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null
                && losesIdentityOnInteraction(item.getType())
                && hasLoreIdentityEvidence(item)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteraction(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (target instanceof ArmorStand || target instanceof ItemFrame) {
            return;
        }
        if (hasLoreIdentityEvidence(itemInHand(event.getPlayer(), event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(PlayerLaunchProjectileEvent event) {
        if (event.shouldConsume() && hasLoreIdentityEvidence(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        if (event.shouldConsume() && hasLoreIdentityEvidence(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReadyArrow(PlayerReadyArrowEvent event) {
        if (hasLoreIdentityEvidence(event.getArrow())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        ItemStack consumable = event.getConsumable();
        if (event.shouldConsumeItem()
                && consumable != null
                && hasLoreIdentityEvidence(consumable)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockPreDispenseEvent event) {
        if (hasLoreIdentityEvidence(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    private void protectBucketUse(PlayerBucketEvent event) {
        if (hasLoreIdentityEvidence(itemInHand(event.getPlayer(), event.getHand()))) {
            event.setCancelled(true);
        }
    }

    private static ItemStack itemInHand(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private boolean containsLoreIdentityEvidence(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (hasLoreIdentityEvidence(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLoreIdentityEvidence(ItemStack item) {
        return identityCodec.hasIdentityEvidence(item);
    }

    private static boolean losesIdentityOnInteraction(Material material) {
        String name = material.name();
        return CONSUMPTIVE_INTERACTION_MATERIALS.contains(name)
                || name.endsWith("_DYE")
                || name.endsWith("_SPAWN_EGG");
    }

    private void beginVoidLoss(Item item, LoreItemIdentity identity) {
        UUID instanceId = identity.instanceId().value();
        if (!tryBegin(instanceId)) {
            return;
        }
        VoidLossUseCase useCase;
        CompletionStage<VoidLossUseCase.PrepareResult> preparation;
        try {
            useCase = Objects.requireNonNull(
                    useCaseSupplier.get(), "void-loss use case supplier returned null");
            VoidLossUseCase.Request request = new VoidLossUseCase.Request(
                    identity,
                    item.getUniqueId(),
                    locationKey(item));
            preparation = Objects.requireNonNull(
                    useCase.prepare(request), "void-loss preparation returned null");
        } catch (RuntimeException exception) {
            logFailure("Could not start terminal void loss", exception);
            finish(instanceId, true);
            return;
        }
        preparation.whenComplete((result, failure) -> {
            if (failure != null) {
                logFailure("Could not prepare terminal void loss", failure);
                finish(instanceId, true);
                return;
            }
            if (result == null || result.status() != VoidLossUseCase.PrepareStatus.PREPARED) {
                finish(instanceId, true);
                return;
            }
            schedulePreparedLoss(useCase, result.prepared());
        });
    }

    private boolean tryBegin(UUID instanceId) {
        synchronized (workflowLock) {
            if (closed) {
                return false;
            }
            Long retryAt = retryAfterNanos.get(instanceId);
            long now = System.nanoTime();
            if (retryAt != null) {
                if (retryAt > now) {
                    return false;
                }
                retryAfterNanos.remove(instanceId);
            }
            if (inFlight.contains(instanceId) || inFlight.size() >= maxInFlight) {
                return false;
            }
            inFlight.add(instanceId);
            return true;
        }
    }

    private void schedulePreparedLoss(VoidLossUseCase useCase, PreparedVoidLoss loss) {
        if (closed) {
            requireReview(
                    useCase,
                    loss,
                    "Plugin stopped before the prepared void removal was scheduled.");
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> applyPreparedLoss(useCase, loss));
        } catch (RuntimeException exception) {
            logFailure("Could not schedule prepared void loss", exception);
            requireReview(useCase, loss, "The prepared void removal could not be scheduled.");
        }
    }

    private void applyPreparedLoss(VoidLossUseCase useCase, PreparedVoidLoss loss) {
        if (closed) {
            requireReview(useCase, loss, "Plugin stopped before the prepared void removal ran.");
            return;
        }
        Entity entity = plugin.getServer().getEntity(loss.entityId());
        if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
            requireReview(useCase, loss, "The prepared item entity was unavailable before removal.");
            return;
        }
        ItemIdentityReadResult observed = identityCodec.readIdentity(item.getItemStack());
        if (!(observed instanceof ItemIdentityReadResult.Tracked tracked)
                || !tracked.identity().equals(loss.identity())) {
            requireReview(useCase, loss, "The item identity changed after void loss was prepared.");
            return;
        }
        if (item.getLocation().getY() >= item.getWorld().getMinHeight()) {
            abort(useCase, loss, "The item was no longer below the world's minimum height.");
            return;
        }
        item.remove();
        complete(useCase, loss, 1);
    }

    private void complete(VoidLossUseCase useCase, PreparedVoidLoss loss, int attempt) {
        CompletionStage<Boolean> completion;
        try {
            completion = Objects.requireNonNull(
                    useCase.complete(loss), "void-loss completion returned null");
        } catch (RuntimeException exception) {
            handleCompletionFailure(useCase, loss, attempt, exception);
            return;
        }
        completion.whenComplete((completed, failure) -> {
            if (failure == null && Boolean.TRUE.equals(completed)) {
                finish(loss.identity().instanceId().value(), false);
                return;
            }
            handleCompletionFailure(useCase, loss, attempt, failure);
        });
    }

    private void handleCompletionFailure(
            VoidLossUseCase useCase,
            PreparedVoidLoss loss,
            int attempt,
            Throwable failure) {
        if (attempt < MAX_COMPLETION_ATTEMPTS && !closed) {
            complete(useCase, loss, attempt + 1);
            return;
        }
        if (failure != null) {
            logFailure("Could not complete terminal void loss", failure);
        }
        requireReview(
                useCase,
                loss,
                "The item entity was removed but durable completion was not confirmed.");
    }

    private void abort(VoidLossUseCase useCase, PreparedVoidLoss loss, String reason) {
        CompletionStage<Boolean> abort;
        try {
            abort = Objects.requireNonNull(
                    useCase.abort(loss, reason), "void-loss abort returned null");
        } catch (RuntimeException exception) {
            logFailure("Could not abort prepared void loss", exception);
            finish(loss.identity().instanceId().value(), true);
            return;
        }
        abort.whenComplete((ignored, failure) -> {
            if (failure != null) {
                logFailure("Could not abort prepared void loss", failure);
            }
            finish(loss.identity().instanceId().value(), true);
        });
    }

    private void requireReview(
            VoidLossUseCase useCase,
            PreparedVoidLoss loss,
            String reason) {
        CompletionStage<Boolean> review;
        try {
            review = Objects.requireNonNull(
                    useCase.requireReview(loss, reason),
                    "void-loss review transition returned null");
        } catch (RuntimeException exception) {
            logFailure("Could not mark void loss for review", exception);
            finish(loss.identity().instanceId().value(), true);
            return;
        }
        review.whenComplete((ignored, failure) -> {
            if (failure != null) {
                logFailure("Could not mark void loss for review", failure);
            }
            finish(loss.identity().instanceId().value(), true);
        });
    }

    private void finish(UUID instanceId, boolean cooldown) {
        synchronized (workflowLock) {
            inFlight.remove(instanceId);
            if (cooldown && !closed && retryAfterNanos.size() < maxCooldowns) {
                retryAfterNanos.put(
                        instanceId,
                        System.nanoTime() + RETRY_COOLDOWN.toNanos());
            }
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
        synchronized (workflowLock) {
            closed = true;
            inFlight.clear();
            retryAfterNanos.clear();
        }
        HandlerList.unregisterAll(this);
    }
}
