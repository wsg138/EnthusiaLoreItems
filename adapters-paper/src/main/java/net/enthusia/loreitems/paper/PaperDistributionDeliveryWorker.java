package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
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

    private final Plugin plugin;
    private final DistributionDeliveryExecutionUseCase useCase;
    private final PaperDirectDeliveryOperator operator;
    private final int claimLimit;
    private final AtomicBoolean claimInFlight = new AtomicBoolean();
    private final PaperDistributionCancellationGate cancellationGate;
    private final PaperDistributionDeliveryOutcomeHandler outcomes;

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
        cancellationGate = new PaperDistributionCancellationGate(claimLimit);
        outcomes = new PaperDistributionDeliveryOutcomeHandler(plugin, useCase, () -> closed);
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
        cancellationGate.begin(campaignId);
    }

    @Override
    public void committed(UUID campaignId) {
        requirePrimaryThread();
        cancellationGate.committed(campaignId, outcomes::cancel, outcomes::cancel);
        requestRun();
    }

    @Override
    public void release(UUID campaignId) {
        requirePrimaryThread();
        cancellationGate.release(
                campaignId,
                this::processClaimedRecipient,
                this::processPrepared);
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
            outcomes.logFailure("Could not submit campaign-delivery wakeup.", exception);
            return;
        }
        wakeup.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                outcomes.logFailure(
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
                            outcomes.logFailure(
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
            outcomes.logFailure(
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
        if (interceptClaim(recipient)) {
            return;
        }
        Player player = plugin.getServer().getPlayer(recipient.playerId());
        if (player == null || !player.isOnline()) {
            outcomes.defer(recipient, CampaignRecipientState.QUEUED_OFFLINE);
            return;
        }
        if (!operator.hasStorageSpace(player)) {
            outcomes.defer(recipient, CampaignRecipientState.QUEUED_INVENTORY_FULL);
            return;
        }
        prepare(recipient);
    }

    private boolean interceptClaim(CampaignRecipient recipient) {
        return switch (cancellationGate.intercept(recipient)) {
            case PROCESS -> false;
            case CANCEL -> {
                outcomes.cancel(recipient);
                yield true;
            }
            case HELD -> true;
            case LEASE_EXPIRY_FALLBACK -> {
                plugin.getLogger().warning(
                        "A campaign cancellation fence reached its bounded held-claim limit; "
                                + "the extra claim will expire without a physical side effect.");
                yield true;
            }
        };
    }

    private void prepare(CampaignRecipient recipient) {
        CompletionStage<java.util.Optional<PreparedDistributionDelivery>> preparation;
        try {
            preparation = Objects.requireNonNull(
                    useCase.prepare(recipient),
                    "campaign-delivery preparation stage");
        } catch (RuntimeException exception) {
            outcomes.requireReview(
                    recipient,
                    "The durable campaign instance reservation could not be submitted.",
                    exception);
            return;
        }
        preparation.whenComplete((prepared, throwable) -> {
            if (throwable != null) {
                outcomes.requireReview(
                        recipient,
                        "The durable campaign instance reservation failed.",
                        throwable);
                return;
            }
            if (prepared == null || prepared.isEmpty()) {
                outcomes.requireReview(
                        recipient,
                        "The campaign recipient claim changed before instance preparation.",
                        null);
                return;
            }
            PreparedDistributionDelivery delivery = prepared.orElseThrow();
            if (!scheduleMain(() -> processPrepared(delivery))) {
                outcomes.requireReview(
                        delivery,
                        "The server stopped before the prepared campaign delivery could be applied.",
                        null);
            }
        });
    }

    private void processPrepared(PreparedDistributionDelivery delivery) {
        if (closed || interceptPrepared(delivery)) {
            return;
        }
        Player player = plugin.getServer().getPlayer(delivery.playerId());
        if (player == null || !player.isOnline()) {
            outcomes.defer(delivery, CampaignRecipientState.QUEUED_OFFLINE);
            return;
        }
        PaperDirectDeliveryOperator.ApplyResult result = operator.apply(player, delivery);
        outcomes.handleApplyResult(delivery, result);
    }

    private boolean interceptPrepared(PreparedDistributionDelivery delivery) {
        return switch (cancellationGate.intercept(delivery)) {
            case PROCESS -> false;
            case CANCEL -> {
                outcomes.cancel(delivery);
                yield true;
            }
            case HELD -> true;
            case LEASE_EXPIRY_FALLBACK -> {
                plugin.getLogger().warning(
                        "A campaign cancellation fence reached its bounded prepared limit; "
                                + "the extra prepared claim will expire into review without insertion.");
                yield true;
            }
        };
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
        cancellationGate.close();
        BukkitTask task = pollTask;
        if (task != null) {
            task.cancel();
        }
    }
}
