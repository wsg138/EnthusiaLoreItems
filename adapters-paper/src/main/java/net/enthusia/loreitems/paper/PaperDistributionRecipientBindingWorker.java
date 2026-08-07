package net.enthusia.loreitems.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.enthusia.loreitems.application.BindDistributionRecipientsUseCase;
import net.enthusia.loreitems.application.DistributionRecipientBindingBatch;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperDistributionRecipientBindingWorker implements Listener, AutoCloseable {
    private static final int MIN_QUEUE_CAPACITY = 32;
    private static final int QUEUE_MULTIPLIER = 8;
    private static final int MAX_QUEUE_CAPACITY = 4_096;
    private static final int MIN_POSITIVE_VALUE = 1;
    private static final int FIRST_CHARACTER_INDEX = 0;
    private static final long NO_TICKS_REMAINING = 0L;
    private static final long PERIODIC_SCAN_TICKS = 200L;

    private final Plugin plugin;
    private final BindingFunction bindingFunction;
    private final Consumer<UUID> deliveryWake;
    private final Predicate<UUID> floodgatePlayer;
    private final Clock clock;
    private final int pageSize;
    private final int mutationBudgetPerTick;
    private final int queueCapacity;
    private final ArrayDeque<IdentityCandidate> pending = new ArrayDeque<>();
    private final Set<IdentityCandidate> scheduled = new HashSet<>();

    private BukkitTask task;
    private Iterator<? extends Player> onlineScan = Collections.emptyIterator();
    private int inFlight;
    private long ticksUntilScan;
    private volatile boolean closed;

    public PaperDistributionRecipientBindingWorker(
            Plugin plugin,
            BindDistributionRecipientsUseCase useCase,
            Consumer<UUID> deliveryWake,
            int pageSize,
            int mutationBudgetPerTick) {
        this(
                plugin,
                Objects.requireNonNull(useCase, "useCase")::bindCurrentName,
                deliveryWake,
                new FloodgateDetector(plugin)::isFloodgatePlayer,
                Clock.systemUTC(),
                pageSize,
                mutationBudgetPerTick);
    }

    PaperDistributionRecipientBindingWorker(
            Plugin plugin,
            BindingFunction bindingFunction,
            Consumer<UUID> deliveryWake,
            Predicate<UUID> floodgatePlayer,
            Clock clock,
            int pageSize,
            int mutationBudgetPerTick) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bindingFunction = Objects.requireNonNull(bindingFunction, "bindingFunction");
        this.deliveryWake = Objects.requireNonNull(deliveryWake, "deliveryWake");
        this.floodgatePlayer = Objects.requireNonNull(floodgatePlayer, "floodgatePlayer");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pageSize < MIN_POSITIVE_VALUE
                || pageSize > net.enthusia.loreitems.application.PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside the supported bounded range");
        }
        if (mutationBudgetPerTick < MIN_POSITIVE_VALUE) {
            throw new IllegalArgumentException("mutationBudgetPerTick must be positive");
        }
        this.pageSize = pageSize;
        this.mutationBudgetPerTick = mutationBudgetPerTick;
        long desiredCapacity = Math.max(
                MIN_QUEUE_CAPACITY,
                Math.multiplyExact((long) mutationBudgetPerTick, QUEUE_MULTIPLIER));
        this.queueCapacity = (int) Math.min(MAX_QUEUE_CAPACITY, desiredCapacity);
    }

    public void start() {
        requirePrimaryThread();
        if (closed) {
            throw new IllegalStateException("Distribution recipient binding worker is closed");
        }
        if (task != null) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        ticksUntilScan = NO_TICKS_REMAINING;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = Objects.requireNonNull(event, "event").getPlayer();
        enqueuePlayer(player);
        deliveryWake.accept(player.getUniqueId());
    }

    void tick() {
        requirePrimaryThread();
        if (closed) {
            return;
        }
        advancePeriodicOnlineScan();
        int dispatched = 0;
        while (dispatched < mutationBudgetPerTick
                && inFlight < mutationBudgetPerTick
                && !pending.isEmpty()) {
            IdentityCandidate candidate = pending.removeFirst();
            inFlight++;
            dispatched++;
            submitBinding(candidate);
        }
    }

    void enqueueIdentity(UUID playerId, String currentName, boolean floodgate) {
        requirePrimaryThread();
        String lookupName = normalizeLookupName(currentName, floodgate);
        IdentityCandidate candidate = new IdentityCandidate(
                Objects.requireNonNull(playerId, "playerId"), lookupName);
        if (scheduled.contains(candidate)) {
            return;
        }
        if (pending.size() + inFlight >= queueCapacity) {
            plugin.getLogger().warning(
                    "Distribution identity wake queue is full; periodic online-player scanning will retry.");
            return;
        }
        scheduled.add(candidate);
        pending.addLast(candidate);
    }

    int pendingCount() {
        return pending.size();
    }

    int inFlightCount() {
        return inFlight;
    }

    private void enqueuePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        try {
            enqueueIdentity(playerId, player.getName(), floodgatePlayer.test(playerId));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning(
                    "Skipping distribution identity binding for unsupported player name '"
                            + player.getName() + "': " + safeMessage(exception));
        }
    }

    private void advancePeriodicOnlineScan() {
        if (!onlineScan.hasNext()) {
            if (ticksUntilScan > NO_TICKS_REMAINING) {
                ticksUntilScan--;
                return;
            }
            onlineScan = Bukkit.getOnlinePlayers().iterator();
            ticksUntilScan = PERIODIC_SCAN_TICKS;
        }
        int scanned = 0;
        while (scanned < mutationBudgetPerTick && onlineScan.hasNext()) {
            enqueuePlayer(onlineScan.next());
            scanned++;
        }
    }

    private void submitBinding(IdentityCandidate candidate) {
        CompletionStage<DistributionRecipientBindingBatch> stage;
        try {
            stage = bindingFunction.bind(
                    candidate.playerId(),
                    candidate.lookupName(),
                    Instant.now(clock),
                    pageSize);
        } catch (RuntimeException exception) {
            finishOnPrimaryThread(candidate, null, exception);
            return;
        }
        if (stage == null) {
            finishOnPrimaryThread(
                    candidate,
                    null,
                    new IllegalStateException("Distribution recipient binder returned null stage"));
            return;
        }
        stage.whenComplete((batch, throwable) -> finishOnPrimaryThread(candidate, batch, throwable));
    }

    private void finishOnPrimaryThread(
            IdentityCandidate candidate,
            DistributionRecipientBindingBatch batch,
            Throwable throwable) {
        Runnable completion = () -> finish(candidate, batch, throwable);
        if (Bukkit.isPrimaryThread()) {
            completion.run();
            return;
        }
        if (closed) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, completion);
        } catch (RuntimeException exception) {
            closed = true;
            plugin.getLogger().warning(
                    "Could not schedule distribution identity completion; "
                            + "binding worker is now closed: " + safeMessage(exception));
        }
    }

    private void finish(
            IdentityCandidate candidate,
            DistributionRecipientBindingBatch batch,
            Throwable throwable) {
        requirePrimaryThread();
        if (inFlight > 0) {
            inFlight--;
        }
        if (closed) {
            scheduled.remove(candidate);
            return;
        }
        if (throwable != null || batch == null) {
            scheduled.remove(candidate);
            plugin.getLogger().warning(
                    "Distribution recipient identity binding failed for "
                            + candidate.lookupName() + ": " + safeMessage(throwable));
            return;
        }
        if (batch.bound() > 0) {
            deliveryWake.accept(candidate.playerId());
        }
        if (batch.hasMore()) {
            if (pending.size() + inFlight < queueCapacity) {
                pending.addLast(candidate);
                return;
            }
            plugin.getLogger().warning(
                    "Distribution identity continuation queue is full; periodic scanning will retry.");
        }
        scheduled.remove(candidate);
    }

    @Override
    public void close() {
        closed = true;
        if (task != null) {
            task.cancel();
        }
        HandlerList.unregisterAll(this);
        onlineScan = Collections.emptyIterator();
        pending.clear();
        scheduled.clear();
    }

    private static String normalizeLookupName(String currentName, boolean floodgate) {
        Objects.requireNonNull(currentName, "currentName");
        String normalized = currentName.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("currentName must be non-blank");
        }
        boolean hasFloodgatePrefix = normalized.charAt(FIRST_CHARACTER_INDEX) == '*';
        if (!floodgate) {
            if (hasFloodgatePrefix) {
                throw new IllegalArgumentException(
                        "non-Floodgate currentName must not use the Floodgate '*' prefix");
            }
            return normalized;
        }
        if (hasFloodgatePrefix) {
            if (normalized.length() == 1) {
                throw new IllegalArgumentException(
                        "Floodgate currentName must contain a name after the '*' prefix");
            }
            return normalized;
        }
        return '*' + normalized;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Distribution binding worker mutation must run on the server thread");
        }
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    @FunctionalInterface
    interface BindingFunction {
        CompletionStage<DistributionRecipientBindingBatch> bind(
                UUID playerId,
                String currentName,
                Instant now,
                int limit);
    }

    private record IdentityCandidate(UUID playerId, String lookupName) {
    }

    private static final class FloodgateDetector {
        private final Plugin plugin;
        private Method getInstanceMethod;
        private Method floodgatePlayerMethod;
        private boolean available;
        private boolean failureLogged;

        private FloodgateDetector(Plugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            initialize();
        }

        private void initialize() {
            if (plugin.getServer().getPluginManager().getPlugin("floodgate") == null) {
                return;
            }
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                getInstanceMethod = api.getMethod("getInstance");
                floodgatePlayerMethod = api.getMethod("isFloodgatePlayer", UUID.class);
                available = true;
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError exception) {
                logFailure(exception);
            }
        }

        private boolean isFloodgatePlayer(UUID playerId) {
            if (!available) {
                return false;
            }
            try {
                Object api = getInstanceMethod.invoke(null);
                return Boolean.TRUE.equals(floodgatePlayerMethod.invoke(api, playerId));
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                available = false;
                logFailure(exception);
                return false;
            }
        }

        private void logFailure(Throwable throwable) {
            if (failureLogged) {
                return;
            }
            failureLogged = true;
            plugin.getLogger().warning(
                    "Floodgate is present but its identity API is unavailable; *Bedrock recipients "
                            + "will remain unresolved until the integration is restored: "
                            + safeMessage(throwable));
        }
    }
}
