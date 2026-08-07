package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.FIRST_PAGE;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.parsePage;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.parsePositive;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.parseState;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.request;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.requireLength;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.requireMinimumLength;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.safeMessage;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.sendPageFooter;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.sendUsage;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.showCatalogPage;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.showPreview;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.showRecipients;
import static net.enthusia.loreitems.paper.DistributionCampaignCommandSupport.showStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.enthusia.loreitems.application.CampaignCancellationResult;
import net.enthusia.loreitems.application.DistributionCampaignAdministrationUseCase;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DistributionCampaign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Staff command surface for one-use mass distribution campaigns. */
public final class DistributionCampaignCommandExecutor
        implements CommandExecutor, TabCompleter, AutoCloseable {
    public static final String INSPECT_PERMISSION =
            "enthusia.loreitems.admin.distribution.inspect";
    public static final String START_PERMISSION =
            "enthusia.loreitems.admin.distribution.start";
    public static final String CONTROL_PERMISSION =
            "enthusia.loreitems.admin.distribution.control";

    private static final int MAX_PENDING_PREVIEWS = 64;

    private final Plugin plugin;
    private final PaperGroupFileCatalog groupCatalog;
    private final PaperDistributionCampaignCoordinator coordinator;
    private final DistributionCampaignAdministrationUseCase administration;
    private final PaperDistributionMarkerReconciler markerReconciler;
    private final DistributionCancellationFence cancellationFence;
    private final Runnable markerWake;
    private final Executor blockingExecutor;
    private final int pageSize;
    private final Map<PreviewKey, DistributionCampaignPreview> previews = new LinkedHashMap<>();

    private boolean closed;

    public DistributionCampaignCommandExecutor(
            Plugin plugin,
            DistributionCampaignCommandDependencies dependencies,
            int pageSize) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        DistributionCampaignCommandDependencies required =
                Objects.requireNonNull(dependencies, "dependencies");
        groupCatalog = required.groupCatalog();
        coordinator = required.coordinator();
        administration = required.administration();
        markerReconciler = required.markerReconciler();
        cancellationFence = required.cancellationFence();
        markerWake = required.markerWake();
        blockingExecutor = required.blockingExecutor();
        if (pageSize < 1 || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside the supported bounded range");
        }
        this.pageSize = pageSize;
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] args) {
        Objects.requireNonNull(sender, "sender");
        if (closed) {
            sender.sendMessage("Distribution campaigns are unavailable while LoreItems is stopping.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        try {
            return route(sender, label, subcommand, args);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            sender.sendMessage("Invalid distribution command: " + safeMessage(exception));
            return true;
        }
    }

    private boolean route(
            CommandSender sender, String label, String subcommand, String[] args) {
        return switch (subcommand) {
            case "reload" -> reload(sender, args);
            case "inspect" -> inspect(sender, args);
            case "preview" -> preview(sender, args);
            case "confirm" -> confirm(sender, args);
            case "campaigns" -> campaigns(sender, args);
            case "status" -> status(sender, args);
            case "recipients" -> recipients(sender, args);
            case "pause" -> transition(sender, args, true);
            case "resume" -> transition(sender, args, false);
            case "cancel" -> cancel(sender, args);
            case "reconcile" -> reconcile(sender, args);
            default -> {
                sendUsage(sender, label);
                yield true;
            }
        };
    }

    private boolean reload(CommandSender sender, String[] args) {
        requirePermission(sender, INSPECT_PERMISSION);
        int pageNumber = parsePage(args, 1);
        CompletionStage<GroupFileCatalogSnapshot> stage = CompletableFuture.supplyAsync(() -> {
            try {
                return groupCatalog.reload();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, blockingExecutor);
        completeOnMain(
                sender,
                stage,
                snapshot -> showCatalogPage(sender, snapshot, pageNumber, pageSize));
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        requirePermission(sender, INSPECT_PERMISSION);
        requireLength(args, 2, "inspect <group.yml>");
        CompletionStage<GroupFileDefinition> stage = CompletableFuture.supplyAsync(() -> {
            try {
                return groupCatalog.inspect(args[1]);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, blockingExecutor);
        completeOnMain(sender, stage, definition -> {
            sender.sendMessage("Group " + definition.sourceName() + " — " + definition.displayName());
            sender.sendMessage("Recipients: " + definition.recipients().size());
            sender.sendMessage("Fingerprint: " + definition.sourceFingerprint());
        });
        return true;
    }

    private boolean preview(CommandSender sender, String[] args) {
        requirePermission(sender, START_PERMISSION);
        requireLength(args, 3, "preview <group.yml> <definition-key>");
        Actor actor = actor(sender);
        CompletionStage<java.util.Optional<DistributionCampaignPreview>> stage = coordinator.preview(
                args[1], new DefinitionKey(args[2]), actor.type(), actor.id());
        completeOnMain(sender, stage, result -> {
            if (result.isEmpty()) {
                sender.sendMessage("No active lore definition matches that definition key.");
                return;
            }
            DistributionCampaignPreview preview = result.orElseThrow();
            storePreview(actor, preview);
            showPreview(sender, preview);
        });
        return true;
    }

    private boolean confirm(CommandSender sender, String[] args) {
        requirePermission(sender, START_PERMISSION);
        requireLength(args, 2, "confirm <campaign-uuid>");
        UUID campaignId = UUID.fromString(args[1]);
        Actor actor = actor(sender);
        DistributionCampaignPreview preview = previews.remove(new PreviewKey(actor.id(), campaignId));
        if (preview == null) {
            sender.sendMessage("No pending preview with that campaign UUID belongs to you.");
            return true;
        }
        completeOnMain(sender, coordinator.confirm(preview), result -> {
            sender.sendMessage(result.detail());
            sender.sendMessage("Campaign: " + result.campaignId());
            if (result.status() == DistributionCampaignConfirmationResult.Status.STARTED
                    || result.status()
                            == DistributionCampaignConfirmationResult.Status.STARTED_MARKER_REPAIR_REQUIRED) {
                markerWake.run();
            }
        });
        return true;
    }

    private boolean campaigns(CommandSender sender, String[] args) {
        requirePermission(sender, INSPECT_PERMISSION);
        int pageNumber = parsePage(args, 1);
        PageRequest page = request(pageNumber, pageSize);
        completeOnMain(sender, administration.listCampaigns(page), result -> {
            sender.sendMessage("Distribution campaigns — page " + pageNumber);
            for (DistributionCampaign campaign : result.items()) {
                sender.sendMessage(campaign.campaignId() + " " + campaign.state()
                        + " " + campaign.sourceName() + " -> " + campaign.displayName());
            }
            sendPageFooter(sender, result.hasMore());
        });
        return true;
    }

    private boolean status(CommandSender sender, String[] args) {
        requirePermission(sender, INSPECT_PERMISSION);
        requireLength(args, 2, "status <campaign-uuid>");
        UUID campaignId = UUID.fromString(args[1]);
        completeOnMain(sender, administration.status(campaignId), result -> {
            if (result.isEmpty()) {
                sender.sendMessage("No distribution campaign has that UUID.");
                return;
            }
            showStatus(sender, result.orElseThrow());
        });
        return true;
    }

    private boolean recipients(CommandSender sender, String[] args) {
        requirePermission(sender, INSPECT_PERMISSION);
        requireMinimumLength(args, 2, "recipients <campaign-uuid> [state|all] [page]");
        UUID campaignId = UUID.fromString(args[1]);
        CampaignRecipientState state = args.length > 2 ? parseState(args[2]) : null;
        int pageNumber = args.length > 3 ? parsePositive(args[3], "page") : FIRST_PAGE;
        completeOnMain(
                sender,
                administration.listRecipients(
                        campaignId, state, request(pageNumber, pageSize)),
                page -> showRecipients(sender, page, state, pageNumber));
        return true;
    }

    private boolean transition(CommandSender sender, String[] args, boolean pause) {
        requirePermission(sender, CONTROL_PERMISSION);
        requireLength(args, 2, (pause ? "pause" : "resume") + " <campaign-uuid>");
        UUID campaignId = UUID.fromString(args[1]);
        Actor actor = actor(sender);
        CompletionStage<Boolean> stage = pause
                ? administration.pause(campaignId, actor.type(), actor.id())
                : administration.resume(campaignId, actor.type(), actor.id());
        completeOnMain(sender, stage, changed -> {
            sender.sendMessage(Boolean.TRUE.equals(changed)
                    ? "Campaign " + (pause ? "paused." : "resumed.")
                    : "Campaign state did not allow that transition.");
            if (!pause && Boolean.TRUE.equals(changed)) {
                markerWake.run();
            }
        });
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        requirePermission(sender, CONTROL_PERMISSION);
        requireLength(args, 2, "cancel <campaign-uuid>");
        UUID campaignId = UUID.fromString(args[1]);
        Actor actor = actor(sender);
        cancellationFence.begin(campaignId);
        CompletionStage<CampaignCancellationResult> stage;
        try {
            stage = administration.cancel(campaignId, actor.type(), actor.id());
        } catch (RuntimeException exception) {
            cancellationFence.committed(campaignId);
            throw exception;
        }
        stage.whenComplete((result, throwable) -> scheduleMain(() ->
                finishCancellation(sender, campaignId, result, throwable)));
        return true;
    }

    private void finishCancellation(
            CommandSender sender,
            UUID campaignId,
            CampaignCancellationResult result,
            Throwable throwable) {
        if (throwable != null || result == null) {
            cancellationFence.committed(campaignId);
            sendFailure(sender, throwable);
            return;
        }
        if (!result.cancelled()) {
            cancellationFence.release(campaignId);
            sender.sendMessage("Campaign was not cancelled; its durable state did not permit it.");
            return;
        }
        cancellationFence.committed(campaignId);
        markerWake.run();
        sender.sendMessage("Campaign cancelled; pending recipients cancelled: "
                + result.recipientsCancelled());
    }

    private boolean reconcile(CommandSender sender, String[] args) {
        requirePermission(sender, CONTROL_PERMISSION);
        int pageNumber = parsePage(args, 1);
        completeOnMain(
                sender,
                markerReconciler.reconcile(request(pageNumber, pageSize)),
                page -> {
                    sender.sendMessage("Distribution marker reconciliation — page " + pageNumber);
                    for (DistributionMarkerReconciliationPage.Entry entry : page.entries()) {
                        sender.sendMessage(entry.campaignId() + " " + entry.campaignState()
                                + " " + entry.status() + " — " + entry.detail());
                    }
                    sender.sendMessage(page.nextPage() == null
                            ? "End of results."
                            : "More results available.");
                });
        return true;
    }

    private void storePreview(Actor actor, DistributionCampaignPreview preview) {
        if (previews.size() >= MAX_PENDING_PREVIEWS) {
            PreviewKey oldest = previews.keySet().iterator().next();
            previews.remove(oldest);
        }
        previews.put(new PreviewKey(actor.id(), preview.campaignId()), preview);
    }

    private <T> void completeOnMain(
            CommandSender sender, CompletionStage<T> stage, Consumer<T> success) {
        Objects.requireNonNull(stage, "stage").whenComplete((value, throwable) ->
                scheduleMain(() -> {
                    if (throwable != null) {
                        sendFailure(sender, throwable);
                    } else {
                        success.accept(value);
                    }
                }));
    }

    private void scheduleMain(Runnable action) {
        if (closed) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            if (!closed) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not schedule distribution command result.",
                        exception);
            }
        }
    }

    private static void sendFailure(CommandSender sender, Throwable throwable) {
        sender.sendMessage("Distribution operation failed safely: " + safeMessage(throwable));
    }

    private static void requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            throw new IllegalArgumentException("You do not have permission for that operation.");
        }
    }

    private static Actor actor(CommandSender sender) {
        return sender instanceof Player player
                ? new Actor("PLAYER", player.getUniqueId().toString())
                : new Actor("CONSOLE", sender.getName());
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            if (sender.hasPermission(INSPECT_PERMISSION)) {
                Collections.addAll(
                        values, "reload", "inspect", "campaigns", "status", "recipients");
            }
            if (sender.hasPermission(START_PERMISSION)) {
                Collections.addAll(values, "preview", "confirm");
            }
            if (sender.hasPermission(CONTROL_PERMISSION)) {
                Collections.addAll(values, "pause", "resume", "cancel", "reconcile");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            values.removeIf(value -> !value.startsWith(prefix));
            return values;
        }
        if (args.length == 3 && "recipients".equalsIgnoreCase(args[0])) {
            List<String> states = new ArrayList<>();
            states.add("all");
            for (CampaignRecipientState state : CampaignRecipientState.values()) {
                states.add(state.name().toLowerCase(Locale.ROOT));
            }
            return states;
        }
        return List.of();
    }

    @Override
    public void close() {
        closed = true;
        previews.clear();
    }

    private record Actor(String type, String id) {
    }

    private record PreviewKey(String actorId, UUID campaignId) {
    }
}
