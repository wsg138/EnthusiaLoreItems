package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
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

// Pending work and queue limits are compound state guarded by workLock; replacing only the map with
// a concurrent map would not make those transitions atomic.
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class PaperDisplayItemListener implements Listener, AutoCloseable {
    private static final int MIN_CAPACITY = 1;
    private static final int PENDING_CAPACITY_MULTIPLIER = 4;
    private static final int MAX_IDENTITIES_PER_WORK = 16;
    private static final String ITEM_FRAME_PATH = "item";
    private static final String SLOT_PREFIX = "slot:";
    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<Plugin, Set<PaperDisplayItemListener>> ACTIVE_LISTENERS =
            new IdentityHashMap<>();
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
    private final IntSupplier maxInFlightSupplier;
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final Object workLock = new Object();
    private final Map<WorkKey, PendingWork> pending = new HashMap<>();
    private final Queue<PendingObservation> queued = new ArrayDeque<>();
    private final ThreadLocal<ArrayDeque<PendingObservation>> submissionTrampoline =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Boolean> submitting =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final CompletableFuture<Void> quiesced = new CompletableFuture<>();

    private int inFlight;
    private volatile boolean closing;
    private volatile boolean closed;

    public PaperDisplayItemListener(
            Plugin plugin,
            Supplier<DisplayItemObservationUseCase> useCaseSupplier,
            int maxInFlight) {
        this(plugin, useCaseSupplier, () -> maxInFlight);
    }

    public PaperDisplayItemListener(
            Plugin plugin,
            Supplier<DisplayItemObservationUseCase> useCaseSupplier,
            IntSupplier maxInFlightSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.maxInFlightSupplier = Objects.requireNonNull(
                maxInFlightSupplier, "maxInFlightSupplier");
        currentMaxInFlight();
        registerListener();
    }

    static CompletionStage<Void> quiescenceFor(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        CompletableFuture<?>[] barriers;
        synchronized (REGISTRY_LOCK) {
            Set<PaperDisplayItemListener> listeners = ACTIVE_LISTENERS.get(plugin);
            if (listeners == null || listeners.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            barriers = listeners.stream()
                    .map(listener -> listener.quiesced)
                    .toArray(CompletableFuture[]::new);
        }
        return CompletableFuture.allOf(barriers);
    }

    private void registerListener() {
        synchronized (REGISTRY_LOCK) {
            ACTIVE_LISTENERS
                    .computeIfAbsent(plugin, ignored -> new HashSet<>())
                    .add(this);
        }
        quiesced.whenComplete((ignored, failure) -> unregisterListener());
    }

    private void unregisterListener() {
        synchronized (REGISTRY_LOCK) {
            Set<PaperDisplayItemListener> listeners = ACTIVE_LISTENERS.get(plugin);
            if (listeners == null) {
                return;
            }
            listeners.remove(this);
            if (listeners.isEmpty()) {
                ACTIVE_LISTENERS.remove(plugin);
            }
        }
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
                && identityCodec.hasIdentityEvidence(event.getItem().getItemStack())) {
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
        DisplayItemObservationUseCase useCase;
        try {
            useCase = Objects.requireNonNull(
                    useCaseSupplier.get(),
                    "display observation use case supplier returned null");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not resolve display-item observation persistence.",
                    exception);
            return;
        }
        WorkKey key = new WorkKey(
                display.getUniqueId(),
                type,
                locationKey(display),
                containerPath,
                source);
        if (!enqueueCandidates(key, identities, useCase)) {
            return;
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

    private boolean enqueueCandidates(
            WorkKey key,
            List<LoreItemIdentity> identities,
            DisplayItemObservationUseCase useCase) {
        synchronized (workLock) {
            if (closing || closed) {
                return false;
            }
            PendingWork existing = pending.get(key);
            if (existing != null) {
                if (!addBounded(existing.candidates(), identities)) {
                    logCandidateOverflow();
                }
                return false;
            }
            if (pending.size() >= currentMaxPending()) {
                plugin.getLogger().warning(
                        "Display-item observation backlog is full; current durable evidence was preserved.");
                return false;
            }
            Set<LoreItemIdentity> candidates = new HashSet<>();
            if (!addBounded(candidates, identities)) {
                logCandidateOverflow();
            }
            pending.put(key, new PendingWork(candidates, useCase));
            return true;
        }
    }

    // Every bounded candidate represents distinct immutable evidence and therefore requires its own
    // request and location value; reusing one mutable object would corrupt asynchronous evidence.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private void reconcile(WorkKey key) {
        PendingWork work;
        synchronized (workLock) {
            work = pending.remove(key);
        }
        if (work == null || closed) {
            return;
        }
        Set<LoreItemIdentity> candidates = work.candidates();

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
            submit(
                    new DisplayItemObservationUseCase.Request(
                            identity,
                            new LocationDescriptor(
                                    key.type(),
                                    key.locationKey(),
                                    key.containerPath()),
                            DisplayItemObservationUseCase.Presence.ABSENT,
                            key.source()),
                    work.useCase());
        }
        if (currentIdentity != null) {
            submit(
                    new DisplayItemObservationUseCase.Request(
                            currentIdentity,
                            new LocationDescriptor(
                                    key.type(),
                                    currentLocation,
                                    key.containerPath()),
                            DisplayItemObservationUseCase.Presence.PRESENT,
                            key.source()),
                    work.useCase());
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

    private void submit(
            DisplayItemObservationUseCase.Request request,
            DisplayItemObservationUseCase useCase) {
        PendingObservation observation = new PendingObservation(request, useCase);
        synchronized (workLock) {
            if (closed) {
                return;
            }
            if (inFlight >= currentMaxInFlight()) {
                if (queued.size() < currentMaxPending()) {
                    queued.add(observation);
                } else {
                    plugin.getLogger().warning(
                            "Display-item persistence backlog is full; current durable evidence was preserved.");
                }
                return;
            }
            inFlight++;
        }
        dispatchSubmission(observation);
    }

    private void dispatchSubmission(PendingObservation observation) {
        ArrayDeque<PendingObservation> trampoline = submissionTrampoline.get();
        trampoline.addLast(observation);
        if (submitting.get()) {
            return;
        }
        submitting.set(Boolean.TRUE);
        try {
            while (true) {
                PendingObservation next = trampoline.pollFirst();
                if (next == null) {
                    break;
                }
                startSubmission(next);
            }
        } finally {
            trampoline.clear();
            submitting.remove();
            submissionTrampoline.remove();
        }
    }

    private void startSubmission(PendingObservation observation) {
        CompletionStage<DisplayItemObservationUseCase.Result> stage;
        try {
            stage = Objects.requireNonNull(
                    observation.useCase().record(observation.request()),
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
                        unwrap(failure));
            }
            finishSubmission();
        });
    }

    private void finishSubmission() {
        Optional<PendingObservation> next = Optional.empty();
        synchronized (workLock) {
            if (inFlight <= 0) {
                throw new IllegalStateException("Display-item in-flight accounting underflow");
            }
            inFlight--;
            if (!queued.isEmpty()
                    && (closed || inFlight < currentMaxInFlight())) {
                next = Optional.of(queued.remove());
                inFlight++;
            }
            completeQuiescenceIfDrained();
        }
        next.ifPresent(this::dispatchSubmission);
    }

    private void completeQuiescenceIfDrained() {
        if (closed && queued.isEmpty() && inFlight == 0) {
            quiesced.complete(null);
        }
    }

    private static boolean addBounded(
            Set<LoreItemIdentity> target,
            List<LoreItemIdentity> identities) {
        for (LoreItemIdentity identity : identities) {
            if (target.contains(identity)) {
                continue;
            }
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

    private int currentMaxInFlight() {
        int value = maxInFlightSupplier.getAsInt();
        if (value < MIN_CAPACITY) {
            throw new IllegalStateException("Configured mutation budget must be positive");
        }
        return value;
    }

    private int currentMaxPending() {
        return Math.multiplyExact(
                currentMaxInFlight(),
                PENDING_CAPACITY_MULTIPLIER);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    private static String locationKey(Entity entity) {
        Location location = entity.getLocation();
        return entity.getWorld().getKey() + ":"
                + location.getBlockX() + ":"
                + location.getBlockY() + ":"
                + location.getBlockZ() + ":"
                + entity.getUniqueId();
    }

    private static String slotPath(EquipmentSlot slot) {
        return SLOT_PREFIX + slot.name().toLowerCase(Locale.ROOT);
    }

    private static EquipmentSlot slotFromPath(String path) {
        return EquipmentSlot.valueOf(
                path.substring(SLOT_PREFIX.length()).toUpperCase(Locale.ROOT));
    }

    @Override
    public void close() {
        synchronized (workLock) {
            if (closing || closed) {
                return;
            }
            closing = true;
        }
        HandlerList.unregisterAll(this);

        List<WorkKey> accepted;
        synchronized (workLock) {
            accepted = new ArrayList<>(pending.keySet());
        }
        accepted.forEach(this::reconcile);

        synchronized (workLock) {
            closed = true;
            pending.clear();
            completeQuiescenceIfDrained();
        }
    }

    private record WorkKey(
            UUID entityId,
            LocationDescriptor.Type type,
            String locationKey,
            String containerPath,
            String source) {}

    private record PendingWork(
            Set<LoreItemIdentity> candidates,
            DisplayItemObservationUseCase useCase) {
        private PendingWork {
            Objects.requireNonNull(candidates, "candidates");
            Objects.requireNonNull(useCase, "useCase");
        }
    }

    private record PendingObservation(
            DisplayItemObservationUseCase.Request request,
            DisplayItemObservationUseCase useCase) {
        private PendingObservation {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(useCase, "useCase");
        }
    }
}
