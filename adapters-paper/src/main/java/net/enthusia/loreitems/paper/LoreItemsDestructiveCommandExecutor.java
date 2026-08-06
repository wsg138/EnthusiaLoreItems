package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Privileged command surface for durable destructive administration. */
@SuppressWarnings({"PMD.AvoidLiteralsInIfCondition", "PMD.CyclomaticComplexity"})
public final class LoreItemsDestructiveCommandExecutor implements AutoCloseable, TabCompleter {
    public static final String REMOVE_PERMISSION = "enthusia.loreitems.admin.remove";
    public static final String PURGE_PERMISSION = "enthusia.loreitems.admin.purge";
    public static final String DELETE_PERMISSION = "enthusia.loreitems.admin.delete";
    public static final String INSPECT_PERMISSION = "enthusia.loreitems.admin.destructive.inspect";
    public static final String CONTROL_PERMISSION = "enthusia.loreitems.admin.destructive.control";
    public static final String REVIEW_PERMISSION = "enthusia.loreitems.admin.destructive.review";

    private static final String REMOVE = "remove";
    private static final String PURGE = "purge";
    private static final String DELETE = "delete";
    private static final String CONFIRM_REMOVE = "confirm-remove";
    private static final String CONFIRM_PURGE = "confirm-purge";
    private static final String CONFIRM_DELETE = "confirm-delete";
    private static final String OPERATIONS = "operations";
    private static final String TARGETS = "targets";
    private static final String METRICS = "destructive-metrics";
    private static final String PAUSE = "pause-operation";
    private static final String RESUME = "resume-operation";
    private static final String REVIEW = "resolve-removal";
    private static final int MAX_IN_FLIGHT = 32;
    private static final int MAX_CONFIRMATIONS = 256;
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5L);

    private final Plugin plugin;
    private final Supplier<DestructiveAdministrationUseCase> useCaseSupplier;
    private final IntSupplier pageSizeSupplier;
    private final Runnable wakeDestructiveWork;
    private final DestructiveConfirmationRegistry confirmations;
    private final Semaphore capacity = new Semaphore(MAX_IN_FLIGHT);
    private final java.util.Set<String> activeActors = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public LoreItemsDestructiveCommandExecutor(
            Plugin plugin,
            Supplier<DestructiveAdministrationUseCase> useCaseSupplier,
            IntSupplier pageSizeSupplier,
            Runnable wakeDestructiveWork) {
        this(
                plugin,
                useCaseSupplier,
                pageSizeSupplier,
                wakeDestructiveWork,
                Clock.systemUTC());
    }

    LoreItemsDestructiveCommandExecutor(
            Plugin plugin,
            Supplier<DestructiveAdministrationUseCase> useCaseSupplier,
            IntSupplier pageSizeSupplier,
            Runnable wakeDestructiveWork,
            Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.pageSizeSupplier = Objects.requireNonNull(pageSizeSupplier, "pageSizeSupplier");
        this.wakeDestructiveWork = Objects.requireNonNull(
                wakeDestructiveWork, "wakeDestructiveWork");
        confirmations = new DestructiveConfirmationRegistry(
                Objects.requireNonNull(clock, "clock"), CONFIRMATION_TTL, MAX_CONFIRMATIONS);
    }

    public boolean handles(String subcommand) {
        return switch (Objects.requireNonNull(subcommand, "subcommand").toLowerCase(Locale.ROOT)) {
            case REMOVE, PURGE, DELETE, CONFIRM_REMOVE, CONFIRM_PURGE, CONFIRM_DELETE,
                    OPERATIONS, TARGETS, METRICS, PAUSE, RESUME, REVIEW -> true;
            default -> false;
        };
    }

    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (closed || arguments.length == 0) {
            sender.sendMessage("Destructive administration is unavailable.");
            return true;
        }
        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        try {
            return switch (subcommand) {
                case REMOVE -> previewExactRemoval(sender, arguments);
                case PURGE -> previewDefinition(sender, arguments, DestructiveOperationType.PURGE_DEFINITION);
                case DELETE -> previewDefinition(sender, arguments, DestructiveOperationType.DELETE_DEFINITION);
                case CONFIRM_REMOVE -> confirm(sender, arguments, DestructiveOperationType.EXACT_INSTANCE_REMOVAL);
                case CONFIRM_PURGE -> confirm(sender, arguments, DestructiveOperationType.PURGE_DEFINITION);
                case CONFIRM_DELETE -> confirm(sender, arguments, DestructiveOperationType.DELETE_DEFINITION);
                case OPERATIONS -> listOperations(sender, arguments);
                case TARGETS -> listTargets(sender, arguments);
                case METRICS -> metrics(sender, arguments);
                case PAUSE -> control(sender, arguments, true);
                case RESUME -> control(sender, arguments, false);
                case REVIEW -> review(sender, arguments);
                default -> false;
            };
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("Invalid destructive command: " + exception.getMessage());
            return true;
        }
    }

    private boolean previewExactRemoval(CommandSender sender, String[] arguments) {
        if (!requirePermission(sender, REMOVE_PERMISSION) || arguments.length != 3) {
            sender.sendMessage(
                    "Usage: /loreitems remove <definition-uuid> <instance-uuid>");
            return true;
        }
        LoreDefinitionId definitionId = new LoreDefinitionId(parseUuid(arguments[1], "definition"));
        LoreInstanceId instanceId = new LoreInstanceId(parseUuid(arguments[2], "instance"));
        return submit(
                sender,
                () -> useCase().preview(new DestructiveAdministrationUseCase.PreviewRequest(
                        DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                        definitionId,
                        instanceId)),
                preview -> showPreview(sender, preview));
    }

    private boolean previewDefinition(
            CommandSender sender,
            String[] arguments,
            DestructiveOperationType operationType) {
        String permission = operationType == DestructiveOperationType.PURGE_DEFINITION
                ? PURGE_PERMISSION
                : DELETE_PERMISSION;
        String route = operationType == DestructiveOperationType.PURGE_DEFINITION ? PURGE : DELETE;
        if (!requirePermission(sender, permission) || arguments.length != 2) {
            sender.sendMessage("Usage: /loreitems " + route + " <definition-uuid>");
            return true;
        }
        LoreDefinitionId definitionId = new LoreDefinitionId(parseUuid(arguments[1], "definition"));
        return submit(
                sender,
                () -> useCase().preview(new DestructiveAdministrationUseCase.PreviewRequest(
                        operationType, definitionId, null)),
                preview -> showPreview(sender, preview));
    }

    private void showPreview(
            CommandSender sender, Optional<DestructiveAdministrationUseCase.Preview> result) {
        if (result.isEmpty()) {
            sender.sendMessage("No matching active lore definition or instance was found.");
            return;
        }
        DestructiveAdministrationUseCase.Preview preview = result.orElseThrow();
        DestructiveConfirmationRegistry.Session session =
                confirmations.remember(actorId(sender), preview);
        sender.sendMessage("Destructive preview for " + preview.displayName()
                + " [" + preview.lookupKey().value() + "] at revision "
                + preview.expectedRevision().value() + ':');
        sender.sendMessage("targets=" + preview.targetCount()
                + ", inaccessible=" + preview.inaccessibleCount()
                + ", queued=" + preview.queuedCount()
                + ", anomalies=" + preview.anomalyCount());
        sender.sendMessage("This snapshot is fixed for five minutes. Confirm with: /loreitems "
                + confirmationRoute(preview.operationType()) + ' '
                + preview.confirmationToken());
        sender.sendMessage("Confirmation session: " + session.idempotencyKey());
    }

    private boolean confirm(
            CommandSender sender,
            String[] arguments,
            DestructiveOperationType operationType) {
        if (!requirePermission(sender, permissionFor(operationType)) || arguments.length != 2) {
            sender.sendMessage("Usage: /loreitems " + confirmationRoute(operationType)
                    + " <confirmation-token>");
            return true;
        }
        Optional<DestructiveConfirmationRegistry.Session> session = confirmations.consume(
                actorId(sender), operationType, arguments[1]);
        if (session.isEmpty()) {
            sender.sendMessage(
                    "No matching unexpired confirmation exists. Run the destructive preview again.");
            return true;
        }
        DestructiveConfirmationRegistry.Session confirmed = session.orElseThrow();
        return submit(
                sender,
                () -> useCase().start(new DestructiveAdministrationUseCase.StartRequest(
                        confirmed.preview(), confirmed.actorId(), confirmed.idempotencyKey())),
                result -> showStartResult(sender, result));
    }

    private void showStartResult(
            CommandSender sender, DestructiveAdministrationUseCase.StartResult result) {
        sender.sendMessage(result.detail());
        if (result.operation() == null) {
            return;
        }
        sender.sendMessage(formatOperation(result.operation()));
        wakeDestructiveWork.run();
    }

    private boolean listOperations(CommandSender sender, String[] arguments) {
        if (!requirePermission(sender, INSPECT_PERMISSION) || arguments.length > 2) {
            sender.sendMessage("Usage: /loreitems operations [page]");
            return true;
        }
        PageRequest page = pageRequest(arguments, 1);
        return submit(
                sender,
                () -> useCase().listOperations(page),
                result -> showOperations(sender, result));
    }

    private void showOperations(
            CommandSender sender, Page<DestructiveAdministrationUseCase.OperationView> page) {
        sender.sendMessage("Destructive operations, page " + pageNumber(page) + ':');
        if (page.items().isEmpty()) {
            sender.sendMessage("No destructive operations are recorded on this page.");
        }
        page.items().forEach(operation -> sender.sendMessage(formatOperation(operation)));
        showPageFooter(sender, page);
    }

    private boolean listTargets(CommandSender sender, String[] arguments) {
        if (!requirePermission(sender, INSPECT_PERMISSION)
                || arguments.length < 2
                || arguments.length > 3) {
            sender.sendMessage("Usage: /loreitems targets <operation-uuid> [page]");
            return true;
        }
        UUID operationId = parseUuid(arguments[1], "operation");
        PageRequest page = pageRequest(arguments, 2);
        return submit(
                sender,
                () -> useCase().listTargets(operationId, page),
                result -> showTargets(sender, operationId, result));
    }

    private void showTargets(
            CommandSender sender,
            UUID operationId,
            Page<DestructiveAdministrationUseCase.TargetView> page) {
        sender.sendMessage("Targets for " + operationId + ", page " + pageNumber(page) + ':');
        if (page.items().isEmpty()) {
            sender.sendMessage("No destructive targets are recorded on this page.");
        }
        page.items().forEach(target -> sender.sendMessage(formatTarget(target)));
        showPageFooter(sender, page);
    }

    private boolean metrics(CommandSender sender, String[] arguments) {
        if (!requirePermission(sender, INSPECT_PERMISSION) || arguments.length != 1) {
            sender.sendMessage("Usage: /loreitems destructive-metrics");
            return true;
        }
        return submit(sender, () -> useCase().metrics(), result -> {
            sender.sendMessage("Destructive queue metrics: active=" + result.activeOperations()
                    + ", paused=" + result.pausedOperations()
                    + ", queued-targets=" + result.queuedTargets()
                    + ", leases=" + result.activeLeases()
                    + ", review=" + result.reviewRequiredTargets()
                    + ", oldest-queued-ms=" + result.oldestQueuedAgeMillis()
                    + ", attempts=" + result.totalAttempts());
        });
    }

    private boolean control(CommandSender sender, String[] arguments, boolean pause) {
        if (!requirePermission(sender, CONTROL_PERMISSION) || arguments.length != 2) {
            sender.sendMessage("Usage: /loreitems " + (pause ? PAUSE : RESUME)
                    + " <operation-uuid>");
            return true;
        }
        DestructiveAdministrationUseCase.ControlRequest request =
                new DestructiveAdministrationUseCase.ControlRequest(
                        parseUuid(arguments[1], "operation"), actorId(sender));
        return submit(
                sender,
                () -> pause ? useCase().pause(request) : useCase().resume(request),
                result -> {
                    sender.sendMessage(result.detail());
                    if (result.operation() != null) {
                        sender.sendMessage(formatOperation(result.operation()));
                    }
                    if (!pause && result.operation() != null) {
                        wakeDestructiveWork.run();
                    }
                });
    }

    private boolean review(CommandSender sender, String[] arguments) {
        if (!requirePermission(sender, REVIEW_PERMISSION) || arguments.length < 5) {
            sender.sendMessage("Usage: /loreitems resolve-removal <operation-uuid> "
                    + "<instance-uuid> <requeue|removed|abort> <evidence>");
            return true;
        }
        DestructiveAdministrationUseCase.ReviewResolution resolution =
                parseResolution(arguments[3]);
        DestructiveAdministrationUseCase.ReviewRequest request =
                new DestructiveAdministrationUseCase.ReviewRequest(
                        parseUuid(arguments[1], "operation"),
                        new LoreInstanceId(parseUuid(arguments[2], "instance")),
                        resolution,
                        actorId(sender),
                        String.join(" ", Arrays.copyOfRange(arguments, 4, arguments.length)));
        return submit(sender, () -> useCase().resolveReview(request), result -> {
            sender.sendMessage(result.detail());
            if (result.target() != null) {
                sender.sendMessage(formatTarget(result.target()));
                if (resolution == DestructiveAdministrationUseCase.ReviewResolution
                        .REQUEUE_NO_SIDE_EFFECT) {
                    wakeDestructiveWork.run();
                }
            }
        });
    }

    private <T> boolean submit(
            CommandSender sender,
            Supplier<CompletionStage<T>> task,
            Consumer<T> success) {
        String actor = actorId(sender);
        if (closed) {
            sender.sendMessage("Destructive administration is unavailable.");
            return true;
        }
        if (!activeActors.add(actor)) {
            sender.sendMessage("Your previous destructive administration request is still running.");
            return true;
        }
        if (!capacity.tryAcquire()) {
            activeActors.remove(actor);
            sender.sendMessage("Destructive administration is busy; retry shortly.");
            return true;
        }
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(task.get(), "destructive command stage");
        } catch (RuntimeException exception) {
            release(actor);
            reportFailure(sender, exception);
            return true;
        }
        stage.whenComplete((result, throwable) -> scheduleResult(() -> {
            release(actor);
            if (closed) {
                return;
            }
            if (throwable != null) {
                reportFailure(sender, throwable);
            } else if (result == null) {
                sender.sendMessage("Destructive administration returned no result.");
            } else {
                success.accept(result);
            }
        }));
        return true;
    }

    private DestructiveAdministrationUseCase useCase() {
        DestructiveAdministrationUseCase useCase = useCaseSupplier.get();
        if (useCase == null) {
            throw new IllegalStateException("Durable destructive administration is not active");
        }
        return useCase;
    }

    private void scheduleResult(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule a destructive command result during shutdown.",
                    exception);
        }
    }

    private void release(String actor) {
        activeActors.remove(actor);
        capacity.release();
    }

    private void reportFailure(CommandSender sender, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        plugin.getLogger().log(Level.SEVERE, "Destructive administration command failed.", cause);
        sender.sendMessage("Destructive administration failed: "
                + cause.getClass().getSimpleName() + safeDetail(cause));
    }

    private PageRequest pageRequest(String[] arguments, int pageArgumentIndex) {
        int pageNumber = arguments.length > pageArgumentIndex
                ? parsePositiveInt(arguments[pageArgumentIndex], "page")
                : 1;
        int pageSize = Math.max(1, Math.min(PageRequest.MAX_LIMIT, pageSizeSupplier.getAsInt()));
        return new PageRequest(Math.multiplyExact(pageNumber - 1, pageSize), pageSize);
    }

    private static int pageNumber(Page<?> page) {
        return page.offset() / page.limit() + 1;
    }

    private static void showPageFooter(CommandSender sender, Page<?> page) {
        sender.sendMessage(page.hasMore()
                ? "More results are available on page " + (pageNumber(page) + 1) + '.'
                : "End of results.");
    }

    private static String formatOperation(
            DestructiveAdministrationUseCase.OperationView operation) {
        return operation.operationId() + " " + operation.operationType()
                + " state=" + operation.state()
                + " targets=" + operation.targetCount()
                + " remaining=" + operation.remainingCount()
                + " claimed=" + operation.claimedCount()
                + " review=" + operation.reviewCount()
                + " completed=" + operation.completedCount()
                + " aborted=" + operation.abortedCount();
    }

    private static String formatTarget(DestructiveAdministrationUseCase.TargetView target) {
        String location = target.expectedLocationType() == null
                ? "unknown"
                : target.expectedLocationType() + ':' + target.expectedLocationKey();
        String error = target.lastError() == null ? "" : " error=" + target.lastError();
        return target.instanceId().value() + " state=" + target.state()
                + " effect=" + target.effectState()
                + " attempts=" + target.attemptCount()
                + " location=" + location
                + " path=" + String.valueOf(target.expectedContainerPath())
                + error;
    }

    private static String confirmationRoute(DestructiveOperationType operationType) {
        return switch (operationType) {
            case EXACT_INSTANCE_REMOVAL -> CONFIRM_REMOVE;
            case PURGE_DEFINITION -> CONFIRM_PURGE;
            case DELETE_DEFINITION -> CONFIRM_DELETE;
        };
    }

    private static String permissionFor(DestructiveOperationType operationType) {
        return switch (operationType) {
            case EXACT_INSTANCE_REMOVAL -> REMOVE_PERMISSION;
            case PURGE_DEFINITION -> PURGE_PERMISSION;
            case DELETE_DEFINITION -> DELETE_PERMISSION;
        };
    }

    private static DestructiveAdministrationUseCase.ReviewResolution parseResolution(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "requeue" -> DestructiveAdministrationUseCase.ReviewResolution
                    .REQUEUE_NO_SIDE_EFFECT;
            case "removed" -> DestructiveAdministrationUseCase.ReviewResolution
                    .MARK_VERIFIED_REMOVED;
            case "abort" -> DestructiveAdministrationUseCase.ReviewResolution
                    .ABORT_NO_SIDE_EFFECT;
            default -> throw new IllegalArgumentException(
                    "resolution must be requeue, removed, or abort");
        };
    }

    private static UUID parseUuid(String input, String name) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a UUID", exception);
        }
    }

    private static int parsePositiveInt(String input, String name) {
        try {
            int value = Integer.parseInt(input);
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer", exception);
        }
    }

    private static boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage("You do not have permission: " + permission);
        return false;
    }

    private static String actorId(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId().toString();
        }
        String value = "sender:" + sender.getName();
        return value.length() <= DestructiveAdministrationUseCase.StartRequest.MAX_ACTOR_LENGTH
                ? value
                : value.substring(0, DestructiveAdministrationUseCase.StartRequest.MAX_ACTOR_LENGTH);
    }

    private static String safeDetail(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "" : ": " + message;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    static List<String> topLevelCompletions(CommandSender sender, String input) {
        Objects.requireNonNull(sender, "sender");
        String prefix = Objects.requireNonNull(input, "input").toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        addIfAllowed(values, sender, REMOVE, REMOVE_PERMISSION);
        addIfAllowed(values, sender, PURGE, PURGE_PERMISSION);
        addIfAllowed(values, sender, DELETE, DELETE_PERMISSION);
        addIfAllowed(values, sender, OPERATIONS, INSPECT_PERMISSION);
        addIfAllowed(values, sender, TARGETS, INSPECT_PERMISSION);
        addIfAllowed(values, sender, METRICS, INSPECT_PERMISSION);
        addIfAllowed(values, sender, PAUSE, CONTROL_PERMISSION);
        addIfAllowed(values, sender, RESUME, CONTROL_PERMISSION);
        addIfAllowed(values, sender, REVIEW, REVIEW_PERMISSION);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 1) {
            return topLevelCompletions(sender, arguments[0]);
        }
        if (arguments.length == 4 && REVIEW.equalsIgnoreCase(arguments[0])) {
            return List.of("requeue", "removed", "abort").stream()
                    .filter(value -> value.startsWith(arguments[3].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private static void addIfAllowed(
            List<String> values,
            CommandSender sender,
            String value,
            String permission) {
        if (sender.hasPermission(permission)) {
            values.add(value);
        }
    }

    @Override
    public void close() {
        closed = true;
        confirmations.clear();
    }
}
