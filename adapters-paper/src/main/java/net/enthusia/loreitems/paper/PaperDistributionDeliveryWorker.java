package net.enthusia.loreitems.paper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.enthusia.loreitems.application.BindDistributionRecipientsUseCase;
import net.enthusia.loreitems.application.DistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperDistributionDeliveryWorker
        implements Listener, DistributionCancellationFence, AutoCloseable {
    private static final long INITIAL_DELAY_TICKS = 1L;
    private static final long POLL_INTERVAL_TICKS = 100L;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final Plugin plugin;
    private final DistributionDeliveryExecutionUseCase useCase;
    private final PaperDirectDeliveryOperator operator;
    private final int claimLimit;
    private final AtomicBoolean claimInFlight = new AtomicBoolean();
    private final Set<UUID> cancellationFences = new HashSet<>();
    private final Set<UUID> committedCancellations = new HashSet<>();
    private final Map<UUID, List<CampaignRecipient>> heldClaims = new HashMap<>();
    private final Map<UUID, List<PreparedDistributionDelivery>> heldPrepared = new HashMap<>();

    private volatile boolean closed;
    private BukkitTask pollTask;

    public PaperDistributionDeliveryWorker(
            Plugin plugin,
            DistributionDeliveryExecutionUseCase useCase,
            PaperDirectDeliveryOperator operator,
            int deliveryClaimBatchSize,
            int mutationBudgetPerTick) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.operator = Objects.requireNonNull(operator, "operator");
        if (deliveryClaimBatchSize < 1 || mutationBudgetPerTick < 1) {
            throw new IllegalArgumentException(
                    "Campaign delivery worker budgets must be positive");
        }
        claimLimit = Math.min(deliveryClaimBatchSize, mutationBudgetPerTick);
    }

    /**
     * Compatibility constructor retained while runtime wiring moves name binding to its dedicated
     * bounded worker.
     */
    public PaperDistributionDeliveryWorker(
            Plugin plugin,
            DistributionDeliveryExecutionUseCase useCase,
            BindDistributionRecipientsUseCase nameBinder,
            PaperDirectDeliveryOperator operator,
            int deliveryClaimBatchSize,
            int mutationBudgetPerTick) {
        this(plugin, useCase, operator, deliveryClaimBatchSize, mutationBudgetPerTick);
        Objects.requireNonNull(nameBinder, "nameBinder");
    }

    public void start() {
        requirePrimaryThread();
        if (closed || pollTask != null) {
            throw new IllegalStateException(
                    "Campaign delivery worker cannot be started");
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

    @Override
    public void begin(UUID campaignId) {
        requirePrimaryThread();
        cancellationFences.add(Objects.requireNonNull(campaignId, "campaignId"));
    }

    @Override
    public void committed(UUID campaignId) {
        requirePrimaryThread();
        UUID id = Objects.requireNonNull(campaignId, "campaignId");
        cancellationFences.add(id);
        committedCancellations.add(id);
        drainHeldCancellation(id);
        requestRun();
    }

    @Override
    public void release(UUID campaignId) {
        requirePrimaryThread();
        UUID id = Objects.requireNonNull(campaignId, "campaignId");
        cancellationFences.remove(id);
        committedCancellations.remove(id);
        drainHeldNormally(id);
    }

    public void wakePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (closed) {
            return;
        }
        CompletionStage<Integer> wakeup;
        try {
            wakeup = Objects.requireNonNull(
                    useCase.wakePlayer(playerId, claimLimit),
                    "campaign-delivery wakeup stage");
        } catch (RuntimeException exception) {
            logFailure("Could not submit campaign-delivery wakeup.", exception);
            return;
        }
        wakeup.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                logFailure(
                        "Could not wake campaign deliveries for a player.",
                        throwable);
                return;
            }
            requestRun();
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        wakePlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        if (human instanceof Player player) {
            wakePlayer(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        wakePlayer(event.getPlayer().getUniqueId());
    }

    private void requestRun() {
        if (closed || !claimInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            CompletionStage<Integer> recovery = Objects.requireNonNull(
                    useCase.recoverExpiredClaims(claimLimit),
                    "campaign-delivery recovery stage");
            recovery.thenCompose(ignored -> Objects.requireNonNull(
                            useCase.claimPending(claimLimit),
                            "campaign-delivery claim stage"))
                    .whenComplete((page, throwable) -> {
                        if (throwable != null) {
                            claimInFlight.set(false);
                            logFailure(
                                    "Could not recover or claim campaign deliveries.",
                                    throwable);
                            return;
                        }
                        if (!scheduleMain(() -> processClaimed(page))) {
                            claimInFlight.set(false);
                        }
                    });
        } catch (RuntimeException exception) {
            claimInFlight.set(false);
            logFailure(
                    "Could not submit campaign-delivery claim work.", exception);
        }
    }

    private void processClaimed(Page<CampaignRecipient> page) {
        try {
            if (closed) {
                return;
            }
            if (page == null) {
                plugin.getLogger().severe(
                        "Campaign-delivery claim work completed without a result page.");
                return;
            }
            for (CampaignRecipient recipient : page.items()) {
                processClaimedRecipient(recipient);
            }
        } finally {
            claimInFlight.set(false);
        }
    }

    private void processClaimedRecipient(CampaignRecipient recipient) {
        UUID campaignId = recipient.campaignId();
        if (cancellationFences.contains(campaignId)) {
            if (committedCancellations.contains(campaignId)) {
                cancel(recipient);
            } else {
                holdClaim(recipient);
            }
            return;
        }
        Player player = plugin.getServer().getPlayer(recipient.playerId());
        if (player == null || !player.isOnline()) {
            defer(recipient, CampaignRecipientState.QUEUED_OFFLINE);
            return;
        }
        if (!operator.hasStorageSpace(player)) {
            defer(recipient, CampaignRecipientState.QUEUED_INVENTORY_FULL);
            return;
        }
        prepare(recipient);
    }

    private void prepare(CampaignRecipient recipient) {
        CompletionStage<java.util.Optional<PreparedDistributionDelivery>> preparation;
        try {
            preparation = Objects.requireNonNull(
                    useCase.prepare(recipient),
                    "campaign-delivery preparation stage");
        } catch (RuntimeException exception) {
            requireReview(
                    recipient,
                    "The durable campaign instance reservation could not be submitted.",
                    exception);
            return;
        }
        preparation.whenComplete((prepared, throwable) -> {
            if (throwable != null) {
                requireReview(
                        recipient,
                        "The durable campaign instance reservation failed.",
                        throwable);
                return;
            }
            if (prepared == null || prepared.isEmpty()) {
                requireReview(
                        recipient,
                        "The campaign recipient claim changed before instance preparation.",
                        null);
                return;
            }
            PreparedDistributionDelivery delivery = prepared.orElseThrow();
            if (!scheduleMain(() -> processPrepared(delivery))) {
                requireReview(
                        delivery,
                        "The server stopped before the prepared campaign delivery could be applied.",
                        null);
            }
        });
    }

    private void processPrepared(PreparedDistributionDelivery delivery) {
        if (closed) {
            return;
        }
        UUID campaignId = delivery.campaignId();
        if (cancellationFences.contains(campaignId)) {
            if (committedCancellations.contains(campaignId)) {
                cancel(delivery);
            } else {
                holdPrepared(delivery);
            }
            return;
        }
        Player player = plugin.getServer().getPlayer(delivery.playerId());
        if (player == null || !player.isOnline()) {
            defer(delivery, CampaignRecipientState.QUEUED_OFFLINE);
            return;
        }
        PaperDirectDeliveryOperator.ApplyResult result = operator.apply(player, delivery);
        switch (result.status()) {
            case APPLIED -> complete(delivery, result);
            case NO_SPACE ->
                    defer(delivery, CampaignRecipientState.QUEUED_INVENTORY_FULL);
            case REVIEW_REQUIRED -> requireReview(delivery, result.detail(), null);
            default -> requireReview(
                    delivery,
                    "The campaign delivery operator returned an unsupported result state.",
                    null);
        }
    }

    private void holdClaim(CampaignRecipient recipient) {
        List<CampaignRecipient> held = heldClaims.computeIfAbsent(
                recipient.campaignId(), ignored -> new ArrayList<>());
        if (held.size() < claimLimit) {
            held.add(recipient);
            return;
        }
        plugin.getLogger().warning(
                "A campaign cancellation fence reached its bounded held-claim limit; "
                        + "the extra claim will expire without a physical side effect.");
    }

    private void holdPrepared(PreparedDistributionDelivery delivery) {
        List<PreparedDistributionDelivery> held = heldPrepared.computeIfAbsent(
                delivery.campaignId(), ignored -> new ArrayList<>());
        if (held.size() < claimLimit) {
            held.add(delivery);
            return;
        }
        plugin.getLogger().warning(
                "A campaign cancellation fence reached its bounded prepared limit; "
                        + "the extra prepared claim will expire into review without insertion.");
    }

    private void drainHeldCancellation(UUID campaignId) {
        List<CampaignRecipient> claims = heldClaims.remove(campaignId);
        if (claims != null) {
            claims.forEach(this::cancel);
        }
        List<PreparedDistributionDelivery> prepared = heldPrepared.remove(campaignId);
        if (prepared != null) {
            prepared.forEach(this::cancel);
        }
    }

    private void drainHeldNormally(UUID campaignId) {
        List<CampaignRecipient> claims = heldClaims.remove(campaignId);
        if (claims != null) {
            claims.forEach(this::processClaimedRecipient);
        }
        List<PreparedDistributionDelivery> prepared = heldPrepared.remove(campaignId);
        if (prepared != null) {
            prepared.forEach(this::processPrepared);
        }
    }

    private void defer(
            CampaignRecipient recipient,
            CampaignRecipientState target) {
        try {
            useCase.defer(recipient, target, RETRY_DELAY)
                    .whenComplete((deferred, throwable) -> {
                        if (throwable != null || !Boolean.TRUE.equals(deferred)) {
                            requireReview(
                                    recipient,
                                    "The campaign recipient could not be safely deferred.",
                                    throwable);
                        }
                    });
        } catch (RuntimeException exception) {
            requireReview(
                    recipient,
                    "The campaign recipient deferral could not be submitted.",
                    exception);
        }
    }

    private void defer(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState target) {
        try {
            useCase.defer(delivery, target, RETRY_DELAY)
                    .whenComplete((deferred, throwable) -> {
                        if (throwable != null || !Boolean.TRUE.equals(deferred)) {
                            requireReview(
                                    delivery,
                                    "The unused prepared campaign instance could not be safely deferred.",
                                    throwable);
                        }
                    });
        } catch (RuntimeException exception) {
            requireReview(
                    delivery,
                    "The prepared campaign deferral could not be submitted.",
                    exception);
        }
    }

    private void cancel(CampaignRecipient recipient) {
        try {
            useCase.cancel(recipient).whenComplete((cancelled, throwable) -> {
                if (throwable != null || !Boolean.TRUE.equals(cancelled)) {
                    logUnsafeCancellation(
                            recipient.campaignId(),
                            recipient.recipientKey().value(),
                            throwable);
                }
            });
        } catch (RuntimeException exception) {
            logUnsafeCancellation(
                    recipient.campaignId(),
                    recipient.recipientKey().value(),
                    exception);
        }
    }

    private void cancel(PreparedDistributionDelivery delivery) {
        try {
            useCase.cancel(delivery).whenComplete((cancelled, throwable) -> {
                if (throwable != null || !Boolean.TRUE.equals(cancelled)) {
                    requireReview(
                            delivery,
                            "The cancelled campaign's unused prepared delivery "
                                    + "could not be safely discarded.",
                            throwable);
                }
            });
        } catch (RuntimeException exception) {
            requireReview(
                    delivery,
                    "The cancelled campaign's prepared delivery cancellation "
                            + "could not be submitted.",
                    exception);
        }
    }

    private void complete(
            PreparedDistributionDelivery delivery,
            PaperDirectDeliveryOperator.ApplyResult result) {
        try {
            useCase.complete(
                            delivery,
                            Objects.requireNonNull(
                                    result.inventorySlot(), "inventorySlot"),
                            Objects.requireNonNull(
                                    result.afterFingerprint(), "afterFingerprint"))
                    .whenComplete((completed, throwable) -> {
                        if (throwable != null || !Boolean.TRUE.equals(completed)) {
                            requireReview(
                                    delivery,
                                    "The item was inserted but campaign completion "
                                            + "could not be persisted.",
                                    throwable);
                        } else {
                            notifyPlayer(
                                    delivery.playerId(),
                                    "A lore-item campaign reward was delivered to your inventory.");
                        }
                    });
        } catch (RuntimeException exception) {
            requireReview(
                    delivery,
                    "The item was inserted but campaign completion could not be submitted.",
                    exception);
        }
    }

    private void requireReview(
            CampaignRecipient recipient,
            String reason,
            Throwable precedingFailure) {
        if (precedingFailure != null) {
            logFailure(
                    "Campaign recipient entered review after an operational failure.",
                    precedingFailure);
        }
        try {
            useCase.requireReview(recipient, reason).whenComplete((reviewed, throwable) -> {
                if (throwable != null) {
                    logFailure(
                            "Could not persist campaign-recipient review state.",
                            throwable);
                } else if (!Boolean.TRUE.equals(reviewed)) {
                    plugin.getLogger().severe(
                            "Campaign recipient could not reach a safe durable state: "
                                    + recipient.campaignId() + '/'
                                    + recipient.recipientKey().value());
                }
            });
        } catch (RuntimeException exception) {
            logFailure(
                    "Could not submit campaign-recipient review persistence.",
                    exception);
        }
    }

    private void requireReview(
            PreparedDistributionDelivery delivery,
            String reason,
            Throwable precedingFailure) {
        if (precedingFailure != null) {
            logFailure(
                    "Prepared campaign delivery entered review after an operational failure.",
                    precedingFailure);
        }
        try {
            useCase.requireReview(delivery, reason).whenComplete((reviewed, throwable) -> {
                if (throwable != null) {
                    logFailure(
                            "Could not persist prepared campaign-delivery review state.",
                            throwable);
                } else if (!Boolean.TRUE.equals(reviewed)) {
                    plugin.getLogger().severe(
                            "Prepared campaign delivery could not reach a safe durable state: "
                                    + delivery.campaignId() + '/'
                                    + delivery.recipientKey().value());
                }
            });
        } catch (RuntimeException exception) {
            logFailure(
                    "Could not submit prepared campaign-delivery review persistence.",
                    exception);
        }
    }

    private void logUnsafeCancellation(
            UUID campaignId,
            String recipientKey,
            Throwable throwable) {
        String message = "A cancelled campaign claim could not be terminalized immediately: "
                + campaignId + '/' + recipientKey
                + ". No physical insertion occurred; bounded expiry recovery will retry.";
        if (throwable == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, unwrap(throwable));
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

    private boolean scheduleMain(Runnable task) {
        if (closed) {
            return false;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule campaign-delivery main-thread work during shutdown.",
                    exception);
            return false;
        }
    }

    private void logFailure(String message, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, message, unwrap(throwable));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception
                && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Campaign delivery fences must run on the server thread");
        }
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        heldClaims.clear();
        heldPrepared.clear();
        cancellationFences.clear();
        committedCancellations.clear();
        BukkitTask task = pollTask;
        if (task != null) {
            task.cancel();
        }
    }
}
