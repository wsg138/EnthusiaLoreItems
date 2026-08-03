package net.enthusia.loreitems.paper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DirectDeliveryRecord;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

public final class LoreItemsAdministrationCommandExecutor implements CommandExecutor {
    public static final String AUDIT_PERMISSION = "enthusia.loreitems.admin.audit";

    private static final String ANOMALIES_SUBCOMMAND = "anomalies";
    private static final String AUDIT_SUBCOMMAND = "audit";
    private static final String RECOVERY_SUBCOMMAND = "recovery";
    private static final String USAGE = "Usage: /loreitems anomalies [page] | "
            + "/loreitems audit <instance-uuid> [page] | /loreitems recovery [page]";
    private static final int MAX_SUMMARY_LENGTH = 180;

    private final Plugin plugin;
    private final int pageSize;

    public LoreItemsAdministrationCommandExecutor(Plugin plugin, int pageSize) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (pageSize < 1 || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside supported bounds");
        }
        this.pageSize = pageSize;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (!sender.hasPermission(AUDIT_PERMISSION)) {
            sender.sendMessage("You do not have permission to inspect lore-item evidence.");
            return true;
        }
        LoreItemsAdministrationUseCase useCase = plugin.getServer()
                .getServicesManager()
                .load(LoreItemsAdministrationUseCase.class);
        if (useCase == null) {
            sender.sendMessage("Lore-item administration is unavailable while storage initializes.");
            return true;
        }
        if (arguments.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }
        CommandActor actor = CommandActor.capture(sender);
        switch (arguments[0].toLowerCase(java.util.Locale.ROOT)) {
            case ANOMALIES_SUBCOMMAND -> executeAnomalies(actor, useCase, arguments);
            case AUDIT_SUBCOMMAND -> executeAudit(actor, useCase, arguments);
            case RECOVERY_SUBCOMMAND -> executeRecovery(actor, useCase, arguments);
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void executeAnomalies(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        PageRequest request = parsePage(actor, arguments, 1);
        if (request == null) {
            return;
        }
        useCase.listActiveAnomalies(request).whenComplete((page, failure) -> {
            if (failure != null) {
                handleFailure(actor, "active anomalies", failure);
                return;
            }
            notifyActor(actor, anomalyLines(page));
        });
    }

    private void executeAudit(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        if (arguments.length < 2 || arguments.length > 3) {
            notifyActor(actor, List.of(USAGE));
            return;
        }
        LoreInstanceId instanceId;
        try {
            instanceId = new LoreInstanceId(UUID.fromString(arguments[1]));
        } catch (IllegalArgumentException exception) {
            notifyActor(actor, List.of("The instance ID must be a valid UUID."));
            return;
        }
        PageRequest request = parsePage(actor, arguments, 2);
        if (request == null) {
            return;
        }
        CompletionStage<Page<AuditEventRecord>> audit =
                useCase.listInstanceAudit(instanceId, request);
        CompletionStage<Page<InstanceAnomaly>> anomalies =
                useCase.listInstanceAnomalies(instanceId, request);
        audit.thenCombine(anomalies, AuditView::new)
                .whenComplete((view, failure) -> {
                    if (failure != null) {
                        handleFailure(actor, "instance audit", failure);
                        return;
                    }
                    notifyActor(actor, auditLines(instanceId, view));
                });
    }

    private void executeRecovery(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        PageRequest request = parsePage(actor, arguments, 1);
        if (request == null) {
            return;
        }
        useCase.listRecovery(request).whenComplete((page, failure) -> {
            if (failure != null) {
                handleFailure(actor, "recovery work", failure);
                return;
            }
            notifyActor(actor, recoveryLines(page));
        });
    }

    private PageRequest parsePage(
            CommandActor actor,
            String[] arguments,
            int pageArgumentIndex) {
        if (arguments.length > pageArgumentIndex + 1) {
            notifyActor(actor, List.of(USAGE));
            return null;
        }
        int pageNumber = 1;
        if (arguments.length > pageArgumentIndex) {
            try {
                pageNumber = Integer.parseInt(arguments[pageArgumentIndex]);
            } catch (NumberFormatException exception) {
                notifyActor(actor, List.of("Page must be a positive whole number."));
                return null;
            }
        }
        if (pageNumber < 1) {
            notifyActor(actor, List.of("Page must be a positive whole number."));
            return null;
        }
        try {
            return new PageRequest(Math.multiplyExact(pageNumber - 1, pageSize), pageSize);
        } catch (ArithmeticException exception) {
            notifyActor(actor, List.of("That page number is too large."));
            return null;
        }
    }

    private static List<String> anomalyLines(Page<InstanceAnomaly> page) {
        List<String> lines = new ArrayList<>();
        lines.add("Active lore-item anomalies — page " + pageNumber(page));
        if (page.items().isEmpty()) {
            lines.add("No active anomalies were found.");
            return lines;
        }
        for (InstanceAnomaly anomaly : page.items()) {
            String instance = anomaly.instanceId() == null
                    ? "unknown-instance"
                    : anomaly.instanceId().value().toString();
            lines.add(anomaly.type().name() + " " + anomaly.status().name()
                    + " instance=" + instance
                    + " anomaly=" + anomaly.anomalyId()
                    + " last=" + Instant.ofEpochMilli(anomaly.lastSeenAtEpochMillis())
                    + " detail=" + summarize(anomaly.detail()));
        }
        if (page.hasMore()) {
            lines.add("More results are available on page " + (pageNumber(page) + 1) + '.');
        }
        return lines;
    }

    private static List<String> auditLines(LoreInstanceId instanceId, AuditView view) {
        List<String> lines = new ArrayList<>();
        lines.add("Lore-item evidence for " + instanceId.value());
        if (view.audit().items().isEmpty() && view.anomalies().items().isEmpty()) {
            lines.add("No audit or anomaly records were found on this page.");
            return lines;
        }
        for (InstanceAnomaly anomaly : view.anomalies().items()) {
            lines.add("ANOMALY " + anomaly.type().name() + " " + anomaly.status().name()
                    + " id=" + anomaly.anomalyId()
                    + " detail=" + summarize(anomaly.detail()));
        }
        for (AuditEventRecord event : view.audit().items()) {
            lines.add("AUDIT " + Instant.ofEpochMilli(event.occurredAtEpochMillis())
                    + " " + event.eventType()
                    + " actor=" + event.actorType() + ':' + safeActor(event.actorId())
                    + " detail=" + summarize(event.detailJson()));
        }
        if (view.audit().hasMore() || view.anomalies().hasMore()) {
            lines.add("More evidence is available on the next page.");
        }
        return lines;
    }

    private static List<String> recoveryLines(
            LoreItemsAdministrationUseCase.RecoveryPage page) {
        List<String> lines = new ArrayList<>();
        lines.add("Nonterminal lore-item recovery work — page "
                + pageNumber(page.deliveries()));
        if (page.deliveries().items().isEmpty() && page.mutations().items().isEmpty()) {
            lines.add("No nonterminal delivery or mutation records were found.");
            return lines;
        }
        for (DirectDeliveryRecord delivery : page.deliveries().items()) {
            lines.add("DELIVERY " + delivery.state().name()
                    + " delivery=" + delivery.deliveryId()
                    + " instance=" + delivery.instanceId().value()
                    + " player=" + delivery.playerId()
                    + " attempts=" + delivery.attemptCount());
        }
        for (PendingMutationRecord mutation : page.mutations().items()) {
            lines.add("MUTATION " + mutation.state().name()
                    + " type=" + mutation.mutationType()
                    + " mutation=" + mutation.mutationId()
                    + " instance=" + nullableInstance(mutation)
                    + " attempts=" + mutation.attemptCount());
        }
        if (page.hasMore()) {
            lines.add("More recovery records are available on the next page.");
        }
        return lines;
    }

    private void handleFailure(CommandActor actor, String operation, Throwable failure) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Could not query lore-item " + operation + '.',
                unwrap(failure));
        notifyActor(actor, List.of("The lore-item " + operation + " query failed."));
    }

    private void notifyActor(CommandActor actor, List<String> lines) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                CommandSender sender = actor.playerId() == null
                        ? plugin.getServer().getConsoleSender()
                        : plugin.getServer().getPlayer(actor.playerId());
                if (sender != null) {
                    lines.forEach(sender::sendMessage);
                }
            });
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule lore-item administration output during shutdown.",
                    exception);
        }
    }

    private static int pageNumber(Page<?> page) {
        return page.offset() / page.limit() + 1;
    }

    private static String safeActor(String actorId) {
        return actorId == null ? "system" : actorId;
    }

    private static String nullableInstance(PendingMutationRecord mutation) {
        return mutation.instanceId() == null
                ? "none"
                : mutation.instanceId().value().toString();
    }

    private static String summarize(String value) {
        String flattened = value.replace('\n', ' ').replace('\r', ' ').strip();
        return flattened.length() <= MAX_SUMMARY_LENGTH
                ? flattened
                : flattened.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    private record CommandActor(UUID playerId) {
        private static CommandActor capture(CommandSender sender) {
            return sender instanceof Player player
                    ? new CommandActor(player.getUniqueId())
                    : new CommandActor(null);
        }
    }

    private record AuditView(
            Page<AuditEventRecord> audit,
            Page<InstanceAnomaly> anomalies) {}
}
