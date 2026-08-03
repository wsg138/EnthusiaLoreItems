package net.enthusia.loreitems.paper;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperDirectDeliveryWorker implements Listener, AutoCloseable {
    private static final long INITIAL_DELAY_TICKS = 1L;
    private static final long POLL_INTERVAL_TICKS = 100L;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final Plugin plugin;
    private final DirectDeliveryExecutionUseCase useCase;
    private final PaperDirectDeliveryOperator operator;
    private final int claimLimit;
    private final AtomicBoolean claimInFlight = new AtomicBoolean();

    private volatile boolean closed;
    private BukkitTask pollTask;

    public PaperDirectDeliveryWorker(
            Plugin plugin,
            DirectDeliveryExecutionUseCase useCase,
            PaperDirectDeliveryOperator operator,
            int deliveryClaimBatchSize,
            int mutationBudgetPerTick) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.operator = Objects.requireNonNull(operator, "operator");
        if (deliveryClaimBatchSize < 1 || mutationBudgetPerTick < 1) {
            throw new IllegalArgumentException("Delivery worker budgets must be positive");
        }
        claimLimit = Math.min(deliveryClaimBatchSize, mutationBudgetPerTick);
    }

    public void start() {
        if (closed || pollTask != null) {
            throw new IllegalStateException("Direct-delivery worker cannot be started");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        try {
            pollTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::requestRun,
                    INITIAL_DELAY_TICKS,
                    POLL_INTERVAL_TICKS);
        } catch (RuntimeException exception) {
            HandlerList.unregisterAll(this);
            throw exception;
        }
    }

    public void wakePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (closed) {
            return;
        }
        useCase.wakePlayer(playerId, claimLimit).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                logFailure("Could not wake queued direct deliveries for a player.", throwable);
                return;
            }
            requestRun();
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        wakePlayer(event.getPlayer().getUniqueId());
    }

    private void requestRun() {
        if (closed || !claimInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            useCase.recoverExpiredClaims(claimLimit)
                    .thenCompose(ignored -> useCase.claimPending(claimLimit))
                    .whenComplete((page, throwable) -> {
                        if (throwable != null) {
                            claimInFlight.set(false);
                            logFailure("Could not recover or claim queued direct deliveries.", throwable);
                            return;
                        }
                        scheduleMain(() -> processClaimed(page));
                    });
        } catch (RuntimeException exception) {
            claimInFlight.set(false);
            logFailure("Could not submit queued direct-delivery claim work.", exception);
        }
    }

    private void processClaimed(Page<PreparedDirectDelivery> page) {
        try {
            if (closed) {
                return;
            }
            for (PreparedDirectDelivery delivery : page.items()) {
                processOne(delivery);
            }
        } finally {
            claimInFlight.set(false);
        }
    }

    private void processOne(PreparedDirectDelivery delivery) {
        Player player = plugin.getServer().getPlayer(delivery.playerId());
        if (player == null || !player.isOnline()) {
            defer(delivery);
            return;
        }
        PaperDirectDeliveryOperator.ApplyResult result = operator.apply(player, delivery);
        switch (result.status()) {
            case APPLIED -> complete(delivery, result);
            case NO_SPACE -> defer(delivery);
            case REVIEW_REQUIRED -> requireReview(delivery, result.detail(), null);
            default -> requireReview(
                    delivery,
                    "Unsupported direct-delivery apply result: " + result.status(),
                    null);
        }
    }

    private void defer(PreparedDirectDelivery delivery) {
        useCase.defer(delivery, RETRY_DELAY).whenComplete((deferred, throwable) -> {
            if (throwable != null) {
                requireReview(
                        delivery,
                        "The safe offline/full-inventory deferral could not be persisted.",
                        throwable);
            } else if (!Boolean.TRUE.equals(deferred)) {
                requireReview(
                        delivery,
                        "The delivery claim was lost before safe deferral.",
                        null);
            }
        });
    }

    private void complete(
            PreparedDirectDelivery delivery,
            PaperDirectDeliveryOperator.ApplyResult result) {
        useCase.complete(
                        delivery,
                        Objects.requireNonNull(result.inventorySlot(), "inventorySlot"),
                        Objects.requireNonNull(result.afterFingerprint(), "afterFingerprint"))
                .whenComplete((completed, throwable) -> {
                    if (throwable != null) {
                        requireReview(
                                delivery,
                                "The item was inserted but durable completion failed.",
                                throwable);
                    } else if (!Boolean.TRUE.equals(completed)) {
                        requireReview(
                                delivery,
                                "The item was inserted but the claimed completion transition was lost.",
                                null);
                    } else {
                        notifyPlayer(
                                delivery.playerId(),
                                "A queued lore item was delivered to your inventory.");
                    }
                });
    }

    private void requireReview(
            PreparedDirectDelivery delivery,
            String reason,
            Throwable precedingFailure) {
        if (precedingFailure != null) {
            logFailure("Direct delivery entered review after an operational failure.", precedingFailure);
        }
        try {
            useCase.requireReview(delivery, reason).whenComplete((reviewed, throwable) -> {
                if (throwable != null) {
                    logFailure("Could not persist direct-delivery review state.", throwable);
                    return;
                }
                if (!Boolean.TRUE.equals(reviewed)) {
                    plugin.getLogger().severe(
                            "Direct delivery could not be completed, deferred, or moved to review: "
                                    + delivery.deliveryId());
                }
            });
        } catch (RuntimeException exception) {
            logFailure("Could not submit direct-delivery review persistence.", exception);
        }
    }

    private void notifyPlayer(UUID playerId, String message) {
        scheduleMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        });
    }

    private void scheduleMain(Runnable task) {
        if (closed) {
            claimInFlight.set(false);
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException exception) {
            claimInFlight.set(false);
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule direct-delivery main-thread work during shutdown.",
                    exception);
        }
    }

    private void logFailure(String message, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, message, unwrap(throwable));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        BukkitTask task = pollTask;
        if (task != null) {
            task.cancel();
        }
    }
}
