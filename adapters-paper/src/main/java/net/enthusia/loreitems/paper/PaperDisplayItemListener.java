package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class PaperDisplayItemListener implements Listener, AutoCloseable {
    private static final int MIN_CAPACITY = 1;
    private static final int PENDING_CAPACITY_MULTIPLIER = 4;
    private static final int MAX_IDENTITIES_PER_WORK = 16;
    private static final String ITEM_FRAME_PATH = "item";
    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    };

    private final Plugin plugin;
    private final Supplier<DisplayItemObservationUseCase> useCaseSupplier;
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final int maxInFlight;
    private final int maxPending;
    private final Object workLock = new Object();
    private final Map<WorkKey, Set<LoreItemIdentity>> pending = new HashMap<>();
    private final Queue<DisplayItemObservationUseCase.Request> queued = new ArrayDeque<>();

    private int inFlight;
    private volatile boolean closed;

    public PaperDisplayItemListener(
            Plugin plugin,
            Supplier<DisplayItemObservationUseCase> useCaseSupplier,
            int maxInFlight) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        if (maxInFlight < MIN_CAPACITY) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.maxPending = Math.multiplyExact(
                maxInFlight, PENDING_CAPACITY_MULTIPLIER);
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Display-item listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)
                && hasLoreIdentityEvidence(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        ItemFrame frame = event.getItemFrame();
        schedule(
                frame,
                LocationDescriptor.Type.ITEM_FRAME,
                ITEM_FRAME_PATH,
                "item-frame-change",
                trackedIdentities(frame.getItem(), event.getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemFrameBreak(HangingBreakEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            schedule(
                    frame,
                    LocationDescriptor.Type.ITEM_FRAME,
                    ITEM_FRAME_PATH,
                    "item-frame-break",
                    trackedIdentities(frame.getItem()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        ArmorStand stand = event.getRightClicked();
        schedule(
                stand,
                LocationDescriptor.Type.ARMOR_STAND,
                slotPath(event.getSlot()),
                "armor-stand-manipulate",
                trackedIdentities(event.getPlayerItem(), event.getArmorStandItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand)) {
            return;
        }
        for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
            List<LoreItemIdentity> identities = trackedIdentities(
                    stand.getEquipment().getItem(slot));
            if (!identities.isEmpty()) {
                schedule(
                        stand,
                        LocationDescriptor.Type.ARMOR_STAND,
                        slotPath(slot),
                        "armor-stand-damage",
                        identities);
            }
        }
    }

    private void schedule(
            Entity display,
            LocationDescriptor.Type type,
            String containerPath,
            String source,
            List<LoreItemIdentity> identities) {
        if (identities.isEmpty()) {
            return;
        }
        WorkKey key = new WorkKey(
                display.getUniqueId(),
                type,
                locationKey(display),
                containerPath,
                source);
        synchronized (workLock) {
            if (closed) {
                return;
            }
            Set<LoreItemIdentity> existing = pending.get(key);
            if (existing != null) {
                if (!addBounded(existing, identities)) {
                    logCandidateOverflow();
                }
                return;
            }
            if (pending.size() >= maxPending) {
                plugin.getLogger().warning(
                        "Display-item observation backlog is full; current durable evidence was preserved.");
                return;
            }
            Set<LoreItemIdentity> candidates = new HashSet<>();
            if (!addBounded(candidates, identities)) {
                logCandidateOverflow();
            }
            pending.put(key, candidates);
        }
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> reconcile(key));
        } catch (RuntimeException exception) {
            synchronized (workLock) {
                pending.remove(key);
            }
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not schedule display-item observation.",
                    exception);
        }
    }

    private void reconcile(WorkKey key) {
        Set<LoreItemIdentity> candidates;
        synchronized (workLock) {
            candidates = pending.remove(key);
        }
        if (candidates == null || closed) {
            return;
        }

        Entity entity = plugin.getServer().getEntity(key.entityId());
        ItemStack currentItem = currentItem(entity, key);
        LoreItemIdentity currentIdentity = trackedIdentity(currentItem);
        if (currentIdentity != null) {
            candidates.remove(currentIdentity);
        }
        String currentLocation = entity == null
                ? key.locationKey()
                : locationKey(entity);

        for (LoreItemIdentity identity : candidates) {
            submit(new DisplayItemObservationUseCase.Request(
                    identity,
                    new LocationDescriptor(
                            key.type(),
                            key.locationKey(),
                            key.containerPath()),
                    DisplayItemObservationUseCase.Presence.ABSENT,
                    key.source()));
        }
        if (currentIdentity != null) {
            submit(new DisplayItemObservationUseCase.Request(
                    currentIdentity,
                    new LocationDescriptor(
                            key.type(),
                            currentLocation,
                            key.containerPath()),
                    DisplayItemObservationUseCase.Presence.PRESENT,
                    key.source()));
        }
    }

    private ItemStack currentItem(Entity entity, WorkKey key) {
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return null;
        }
        if (key.type() == LocationDescriptor.Type.ITEM_FRAME
                && entity instanceof ItemFrame frame) {
            return frame.getItem();
        }
        if (key.type() == LocationDescriptor.Type.ARMOR_STAND
                && entity instanceof ArmorStand stand) {
            return stand.getEquipment().getItem(slotFromPath(key.containerPath()));
        }
        return null;
    }

    private void submit(DisplayItemObservationUseCase.Request request) {
        synchronized (workLock) {
            if (closed) {
                return;
            }
            if (inFlight >= maxInFlight) {
                if (queued.size() < maxPending) {
                    queued.add(request);
                } else {
                    plugin.getLogger().warning(
                            "Display-item persistence backlog is full; current durable evidence was preserved.");
                }
                return;
            }
            inFlight++;
        }
        startSubmission(request);
    }

    private void startSubmission(DisplayItemObservationUseCase.Request request) {
        CompletionStage<DisplayItemObservationUseCase.Result> stage;
        try {
            DisplayItemObservationUseCase useCase = Objects.requireNonNull(
                    useCaseSupplier.get(),
                    "display observation use case supplier returned null");
            stage = Objects.requireNonNull(
                    useCase.record(request),
                    "display observation use case returned null");
        } catch (RuntimeException exception) {
            finishSubmission();
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start display-item observation.",
                    exception);
            return;
        }
        stage.whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not persist display-item observation.",
                        failure);
            }
            finishSubmission();
        });
    }

    private void finishSubmission() {
        DisplayItemObservationUseCase.Request next = null;
        synchronized (workLock) {
            inFlight--;
            if (!closed && !queued.isEmpty()) {
                next = queued.remove();
                inFlight++;
            }
        }
        if (next != null) {
            startSubmission(next);
        }
    }

    private static boolean addBounded(
            Set<LoreItemIdentity> target,
            List<LoreItemIdentity> identities) {
        for (LoreItemIdentity identity : identities) {
            if (target.size() >= MAX_IDENTITIES_PER_WORK) {
                return false;
            }
            target.add(identity);
        }
        return true;
    }

    private void logCandidateOverflow() {
        plugin.getLogger().warning(
                "A display slot changed too many identities in one tick; durable evidence was preserved.");
    }

    private List<LoreItemIdentity> trackedIdentities(ItemStack... items) {
        List<LoreItemIdentity> identities = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            LoreItemIdentity identity = trackedIdentity(item);
            if (identity != null && !identities.contains(identity)) {
                identities.add(identity);
            }
        }
        return identities;
    }

    private LoreItemIdentity trackedIdentity(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                ? tracked.identity()
                : null;
    }

    private boolean hasLoreIdentityEvidence(ItemStack item) {
        return !(identityCodec.readIdentity(item)
                instanceof ItemIdentityReadResult.Untracked);
    }

    private static String locationKey(Entity entity) {
        return entity.getWorld().getKey() + ":"
                + entity.getLocation().getBlockX() + ":"
                + entity.getLocation().getBlockY() + ":"
                + entity.getLocation().getBlockZ() + ":"
                + entity.getUniqueId();
    }

    private static String slotPath(EquipmentSlot slot) {
        return "slot:" + slot.name().toLowerCase(Locale.ROOT);
    }

    private static EquipmentSlot slotFromPath(String path) {
        return EquipmentSlot.valueOf(
                path.substring("slot:".length()).toUpperCase(Locale.ROOT));
    }

    @Override
    public void close() {
        synchronized (workLock) {
            closed = true;
            pending.clear();
            queued.clear();
        }
        HandlerList.unregisterAll(this);
    }

    private record WorkKey(
            UUID entityId,
            LocationDescriptor.Type type,
            String locationKey,
            String containerPath,
            String source) {}
}
