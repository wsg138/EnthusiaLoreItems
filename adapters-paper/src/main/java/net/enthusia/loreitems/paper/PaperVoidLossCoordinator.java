package net.enthusia.loreitems.paper;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;

// The in-flight set and retry map form one compound state machine guarded by workflowLock.
// Replacing only the map with ConcurrentHashMap would not make those transitions atomic.
@SuppressWarnings("PMD.UseConcurrentHashMap")
final class PaperVoidLossCoordinator implements AutoCloseable {
    private static final int MAX_COMPLETION_ATTEMPTS = 3;
    private static final int MIN_IN_FLIGHT = 1;
    private static final int COOLDOWN_CAPACITY_MULTIPLIER = 4;
    private static final long COMPLETION_RETRY_DELAY_TICKS = 1L;
    private static final Duration RETRY_COOLDOWN = Duration.ofSeconds(5);

    private final Plugin plugin;
    private final Supplier<VoidLossUseCase> useCaseSupplier;
    private final IntSupplier maxInFlightSupplier;
    private final PaperItemIdentityCodec identityCodec;
    private final Object workflowLock = new Object();
    private final Set<UUID> inFlight = new HashSet<>();
    private final Map<UUID, Long> retryAfterNanos = new HashMap<>();

    private volatile boolean closed;

    PaperVoidLossCoordinator(
            Plugin plugin,
            Supplier<VoidLossUseCase> useCaseSupplier,
            IntSupplier maxInFlightSupplier,
            PaperItemIdentityCodec identityCodec) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.maxInFlightSupplier = Objects.requireNonNull(
                maxInFlightSupplier, "maxInFlightSupplier");
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
        currentMaxInFlight();
    }

    void begin(Item item, LoreItemIdentity identity) {
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
            if (inFlight.contains(instanceId)
                    || inFlight.size() >= currentMaxInFlight()) {
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
            requireReview(
                    useCase,
                    loss,
                    "The prepared item entity was unavailable before removal.");
            return;
        }
        ItemIdentityReadResult observed = identityCodec.readIdentity(item.getItemStack());
        if (!(observed instanceof ItemIdentityReadResult.Tracked tracked)
                || !tracked.identity().equals(loss.identity())) {
            requireReview(
                    useCase,
                    loss,
                    "The item identity changed after void loss was prepared.");
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
            try {
                plugin.getServer().getScheduler().runTaskLater(
                        plugin,
                        () -> complete(useCase, loss, attempt + 1),
                        COMPLETION_RETRY_DELAY_TICKS);
                return;
            } catch (RuntimeException exception) {
                logFailure("Could not schedule terminal void-loss completion retry", exception);
            }
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
            int maxCooldowns = Math.multiplyExact(
                    currentMaxInFlight(),
                    COOLDOWN_CAPACITY_MULTIPLIER);
            if (cooldown && !closed && retryAfterNanos.size() < maxCooldowns) {
                retryAfterNanos.put(
                        instanceId,
                        System.nanoTime() + RETRY_COOLDOWN.toNanos());
            }
        }
    }

    private int currentMaxInFlight() {
        int value = maxInFlightSupplier.getAsInt();
        if (value < MIN_IN_FLIGHT) {
            throw new IllegalStateException("Configured mutation budget must be positive");
        }
        return value;
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
    }
}
