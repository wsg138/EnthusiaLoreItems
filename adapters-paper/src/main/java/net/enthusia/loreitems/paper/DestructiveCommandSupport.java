package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Parsing, presentation, and completion helpers kept outside the command dispatcher. */
final class DestructiveCommandSupport {
    private DestructiveCommandSupport() {}

    static PageRequest pageRequest(
            String[] arguments, int pageArgumentIndex, int configuredPageSize) {
        int pageNumber = arguments.length > pageArgumentIndex
                ? parsePositiveInt(arguments[pageArgumentIndex], "page")
                : 1;
        int pageSize = Math.max(1, Math.min(PageRequest.MAX_LIMIT, configuredPageSize));
        return new PageRequest(Math.multiplyExact(pageNumber - 1, pageSize), pageSize);
    }

    static void showOperations(
            CommandSender sender, Page<DestructiveAdministrationUseCase.OperationView> page) {
        sender.sendMessage("Destructive operations, page " + pageNumber(page) + ':');
        if (page.items().isEmpty()) {
            sender.sendMessage("No destructive operations are recorded on this page.");
        }
        page.items().forEach(operation -> sender.sendMessage(formatOperation(operation)));
        showPageFooter(sender, page);
    }

    static void showTargets(
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

    static void showMetrics(
            CommandSender sender, DestructiveAdministrationUseCase.Metrics metrics) {
        sender.sendMessage("Destructive queue metrics: active=" + metrics.activeOperations()
                + ", paused=" + metrics.pausedOperations()
                + ", queued-targets=" + metrics.queuedTargets()
                + ", leases=" + metrics.activeLeases()
                + ", review=" + metrics.reviewRequiredTargets()
                + ", oldest-queued-ms=" + metrics.oldestQueuedAgeMillis()
                + ", attempts=" + metrics.totalAttempts());
    }

    static String formatOperation(DestructiveAdministrationUseCase.OperationView operation) {
        return operation.operationId() + " " + operation.operationType()
                + " state=" + operation.state()
                + " targets=" + operation.targetCount()
                + " remaining=" + operation.remainingCount()
                + " claimed=" + operation.claimedCount()
                + " review=" + operation.reviewCount()
                + " completed=" + operation.completedCount()
                + " aborted=" + operation.abortedCount();
    }

    static String formatTarget(DestructiveAdministrationUseCase.TargetView target) {
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

    static String confirmationRoute(DestructiveOperationType operationType) {
        return switch (operationType) {
            case EXACT_INSTANCE_REMOVAL -> "confirm-remove";
            case PURGE_DEFINITION -> "confirm-purge";
            case DELETE_DEFINITION -> "confirm-delete";
        };
    }

    static String permissionFor(DestructiveOperationType operationType) {
        return switch (operationType) {
            case EXACT_INSTANCE_REMOVAL -> LoreItemsDestructiveCommandExecutor.REMOVE_PERMISSION;
            case PURGE_DEFINITION -> LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION;
            case DELETE_DEFINITION -> LoreItemsDestructiveCommandExecutor.DELETE_PERMISSION;
        };
    }

    static DestructiveAdministrationUseCase.ReviewResolution parseResolution(String input) {
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

    static UUID parseUuid(String input, String name) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a UUID", exception);
        }
    }

    static boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage("You do not have permission: " + permission);
        return false;
    }

    static String actorId(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId().toString();
        }
        String value = "sender:" + sender.getName();
        return value.length() <= DestructiveAdministrationUseCase.StartRequest.MAX_ACTOR_LENGTH
                ? value
                : value.substring(0, DestructiveAdministrationUseCase.StartRequest.MAX_ACTOR_LENGTH);
    }

    static String safeDetail(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "" : ": " + message;
    }

    static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    static List<String> topLevelCompletions(CommandSender sender, String input) {
        Objects.requireNonNull(sender, "sender");
        String prefix = Objects.requireNonNull(input, "input").toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        addIfAllowed(values, sender, "remove", LoreItemsDestructiveCommandExecutor.REMOVE_PERMISSION);
        addIfAllowed(values, sender, "purge", LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION);
        addIfAllowed(values, sender, "delete", LoreItemsDestructiveCommandExecutor.DELETE_PERMISSION);
        addIfAllowed(
                values,
                sender,
                "operations",
                LoreItemsDestructiveCommandExecutor.INSPECT_PERMISSION);
        addIfAllowed(
                values,
                sender,
                "targets",
                LoreItemsDestructiveCommandExecutor.INSPECT_PERMISSION);
        addIfAllowed(
                values,
                sender,
                "destructive-metrics",
                LoreItemsDestructiveCommandExecutor.INSPECT_PERMISSION);
        addIfAllowed(
                values,
                sender,
                "pause-operation",
                LoreItemsDestructiveCommandExecutor.CONTROL_PERMISSION);
        addIfAllowed(
                values,
                sender,
                "resume-operation",
                LoreItemsDestructiveCommandExecutor.CONTROL_PERMISSION);
        addIfAllowed(
                values,
                sender,
                "resolve-removal",
                LoreItemsDestructiveCommandExecutor.REVIEW_PERMISSION);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
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

    private static int pageNumber(Page<?> page) {
        return page.offset() / page.limit() + 1;
    }

    private static void showPageFooter(CommandSender sender, Page<?> page) {
        sender.sendMessage(page.hasMore()
                ? "More results are available on page " + (pageNumber(page) + 1) + '.'
                : "End of results.");
    }

    private static void addIfAllowed(
            List<String> values, CommandSender sender, String value, String permission) {
        if (sender.hasPermission(permission)) {
            values.add(value);
        }
    }
}
