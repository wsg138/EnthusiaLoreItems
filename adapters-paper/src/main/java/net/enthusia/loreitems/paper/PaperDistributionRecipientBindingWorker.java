package net.enthusia.loreitems.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private int inFlight;
    private int scanCursor;
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
        if (pageSize < 1 || pageSize > net.enthusia.loreitems.application.PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside the supported bounded range");
        }
        if (mutationBudgetPerTick < 1) {
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
        ticksUntilScan = 0L;
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
        if (ticksUntilScan <= 0L) {
            enqueuePeriodicOnlineScan();
            ticksUntilScan = PERIODIC_SCAN_TICKS;
        } else {
            ticksUntilScan--;
        }
        while (inFlight < mutationBudgetPerTick && !pending.isEmpty()) {
            IdentityCandidate candidate = pending.removeFirst();
            inFlight++;
            submitBinding(candidate);
        }
    }

    void enqueueIdentity(UUID playerId, String currentName, boolean floodgate) {
        requirePrimaryThread();
        String normalizedName = normalizeName(currentName);
        String lookupName = floodgate ? '*' + normalizedName : normalizedName;
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
        enqueueIdentity(playerId, player.getName(), floodgatePlayer.test(playerId));
    }

    private void enqueuePeriodicOnlineScan() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            scanCursor = 0;
            return;
        }
        int count = Math.min(mutationBudgetPerTick, online.size());
        int start = Math.floorMod(scanCursor, online.size());
        for (int index = 0; index < count; index++) {
            Player player = online.get((start + index) % online.size());
            enqueuePlayer(player);
        }
        scanCursor = (start + count) % online.size();
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
            plugin.getLogger().warning(
                    "Could not schedule distribution identity completion: " + safeMessage(exception));
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
        if (closed) {
            return;
        }
        closed = true;
        BukkitTask currentTask = task;
        task = null;
        if (currentTask != null) {
            currentTask.cancel();
        }
        HandlerList.unregisterAll(this);
        pending.clear();
        scheduled.clear();
    }

    private static String normalizeName(String currentName) {
        Objects.requireNonNull(currentName, "currentName");
        String normalized = currentName.strip();
        if (normalized.isEmpty() || normalized.charAt(0) == '*') {
            throw new IllegalArgumentException("currentName must be an unprefixed non-blank player name");
        }
        return normalized;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Distribution binding worker mutation must run on the server thread");
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
        private Method getInstance;
        private Method isFloodgatePlayer;
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
                getInstance = api.getMethod("getInstance");
                isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
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
                Object api = getInstance.invoke(null);
                return Boolean.TRUE.equals(isFloodgatePlayer.invoke(api, playerId));
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
