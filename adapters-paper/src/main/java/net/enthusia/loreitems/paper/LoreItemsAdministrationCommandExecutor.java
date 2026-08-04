package net.enthusia.loreitems.paper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DirectDeliveryRecord;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
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
    private static final int MAX_CONCURRENT_QUERIES = 32;
    private static final int MIN_PAGE_NUMBER = 1;
    private static final int MIN_AUDIT_ARGUMENTS = 2;
    private static final int MAX_AUDIT_ARGUMENTS = 3;
    private static final int AUDIT_PAGE_ARGUMENT_INDEX = 2;

    private final Plugin plugin;
    private final IntSupplier pageSizeSupplier;
    private final Set<CommandActor> activeActors = ConcurrentHashMap.newKeySet();
    private final Semaphore queryCapacity = new Semaphore(MAX_CONCURRENT_QUERIES);

    public LoreItemsAdministrationCommandExecutor(Plugin plugin, int pageSize) {
        this(plugin, () -> pageSize);
    }

    public LoreItemsAdministrationCommandExecutor(
            Plugin plugin,
            IntSupplier pageSizeSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pageSizeSupplier = Objects.requireNonNull(pageSizeSupplier, "pageSizeSupplier");
        validatePageSize(currentPageSize());
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
        LoreItemsAdministrationUseCase useCase = resolveUseCase(sender);
        if (useCase == null) {
            return true;
        }
        String subcommand = parseSubcommand(sender, arguments);
        if (subcommand == null) {
            return true;
        }
        CommandActor actor = CommandActor.capture(sender);
        if (!beginQuery(actor)) {
            sender.sendMessage("A previous lore-item evidence query is still active; try again shortly.");
            return true;
        }
        dispatchQuery(subcommand, actor, useCase, arguments);
        return true;
    }

    private LoreItemsAdministrationUseCase resolveUseCase(CommandSender sender) {
        if (!sender.hasPermission(AUDIT_PERMISSION)) {
            sender.sendMessage("You do not have permission to inspect lore-item evidence.");
            return null;
        }
        LoreItemsAdministrationUseCase useCase = plugin.getServer()
                .getServicesManager()
                .load(LoreItemsAdministrationUseCase.class);
        if (useCase == null) {
            sender.sendMessage("Lore-item administration is unavailable while storage initializes.");
        }
        return useCase;
    }

    private static String parseSubcommand(CommandSender sender, String[] arguments) {
        if (arguments.length == 0) {
            sender.sendMessage(USAGE);
            return null;
        }
        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        if (!isSupportedSubcommand(subcommand)) {
            sender.sendMessage(USAGE);
            return null;
        }
        return subcommand;
    }

    private static boolean isSupportedSubcommand(String subcommand) {
        return switch (subcommand) {
            case ANOMALIES_SUBCOMMAND, AUDIT_SUBCOMMAND, RECOVERY_SUBCOMMAND -> true;
            default -> false;
        };
    }

    private void dispatchQuery(
            String subcommand,
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        switch (subcommand) {
            case ANOMALIES_SUBCOMMAND -> executeAnomalies(actor, useCase, arguments);
            case AUDIT_SUBCOMMAND -> executeAudit(actor, useCase, arguments);
            case RECOVERY_SUBCOMMAND -> executeRecovery(actor, useCase, arguments);
            default -> {
                finishQuery(actor);
                throw new IllegalStateException("Validated administration subcommand was lost");
            }
        }
    }

    private void executeAnomalies(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        PageRequest request = parsePage(actor, arguments, 1);
        if (request == null) {
            finishQuery(actor);
            return;
        }
        CompletionStage<Page<InstanceAnomaly>> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.listActiveAnomalies(request),
                    "active anomaly query stage");
        } catch (RuntimeException exception) {
            finishQuery(actor);
            handleFailure(actor, "active anomalies", exception);
            return;
        }
        stage.whenComplete((page, failure) -> {
            finishQuery(actor);
            if (failure != null) {
                handleFailure(actor, "active anomalies", failure);
                return;
            }
            if (page == null) {
                handleFailure(actor, "active anomalies",
                        new IllegalStateException("Active anomaly query returned no page"));
                return;
            }
            notifyActor(actor, anomalyLines(page));
        });
    }

    private void executeAudit(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        AuditRequest request = parseAuditRequest(actor, arguments);
        if (request == null) {
            finishQuery(actor);
            return;
        }
        CompletionStage<AuditView> auditView = submitAuditView(actor, useCase, request);
        if (auditView != null) {
            auditView.whenComplete((view, failure) ->
                    completeAuditQuery(actor, request.instanceId(), view, failure));
        }
    }

    private AuditRequest parseAuditRequest(CommandActor actor, String[] arguments) {
        if (arguments.length < MIN_AUDIT_ARGUMENTS
                || arguments.length > MAX_AUDIT_ARGUMENTS) {
            notifyActor(actor, List.of(USAGE));
            return null;
        }
        LoreInstanceId instanceId;
        try {
            instanceId = new LoreInstanceId(UUID.fromString(arguments[1]));
        } catch (IllegalArgumentException exception) {
            notifyActor(actor, List.of("The instance ID must be a valid UUID."));
            return null;
        }
        PageRequest page = parsePage(actor, arguments, AUDIT_PAGE_ARGUMENT_INDEX);
        return page == null ? null : new AuditRequest(instanceId, page);
    }

    private CompletionStage<AuditView> submitAuditView(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            AuditRequest request) {
        try {
            CompletionStage<Optional<InstanceCurrentState>> currentState = Objects.requireNonNull(
                    useCase.findCurrentState(request.instanceId()),
                    "current-state query stage");
            CompletionStage<Page<InstanceObservation>> observations = Objects.requireNonNull(
                    useCase.listInstanceObservations(request.instanceId(), request.page()),
                    "observation query stage");
            CompletionStage<Page<InstanceAnomaly>> anomalies = Objects.requireNonNull(
                    useCase.listInstanceAnomalies(request.instanceId(), request.page()),
                    "instance anomaly query stage");
            CompletionStage<Page<AuditEventRecord>> audit = Objects.requireNonNull(
                    useCase.listInstanceAudit(request.instanceId(), request.page()),
                    "audit-event query stage");
            return combineAuditView(currentState, observations, anomalies, audit);
        } catch (RuntimeException exception) {
            finishQuery(actor);
            handleFailure(actor, "instance audit", exception);
            return null;
        }
    }

    private static CompletionStage<AuditView> combineAuditView(
            CompletionStage<Optional<InstanceCurrentState>> currentState,
            CompletionStage<Page<InstanceObservation>> observations,
            CompletionStage<Page<InstanceAnomaly>> anomalies,
            CompletionStage<Page<AuditEventRecord>> audit) {
        CompletionStage<StateEvidence> stateEvidence = currentState.thenCombine(
                observations,
                StateEvidence::new);
        CompletionStage<HistoryEvidence> historyEvidence = anomalies.thenCombine(
                audit,
                HistoryEvidence::new);
        return stateEvidence.thenCombine(
                historyEvidence,
                (state, history) -> new AuditView(
                        state.currentState(),
                        state.observations(),
                        history.anomalies(),
                        history.audit()));
    }

    private void completeAuditQuery(
            CommandActor actor,
            LoreInstanceId instanceId,
            AuditView view,
            Throwable failure) {
        finishQuery(actor);
        if (failure != null) {
            handleFailure(actor, "instance audit", failure);
            return;
        }
        if (view == null) {
            handleFailure(actor, "instance audit",
                    new IllegalStateException("Instance audit query returned no view"));
            return;
        }
        notifyActor(actor, auditLines(instanceId, view));
    }

    private void executeRecovery(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        PageRequest request = parsePage(actor, arguments, 1);
        if (request == null) {
            finishQuery(actor);
            return;
        }
        CompletionStage<LoreItemsAdministrationUseCase.RecoveryPage> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.listRecovery(request),
                    "recovery query stage");
        } catch (RuntimeException exception) {
            finishQuery(actor);
            handleFailure(actor, "recovery work", exception);
            return;
        }
        stage.whenComplete((page, failure) -> {
            finishQuery(actor);
            if (failure != null) {
                handleFailure(actor, "recovery work", failure);
                return;
            }
            if (page == null) {
                handleFailure(actor, "recovery work",
                        new IllegalStateException("Recovery query returned no page"));
                return;
            }
            notifyActor(actor, recoveryLines(page));
        });
    }

    private PageRequest parsePage(
            CommandActor actor,
            String[] arguments,
            int pageArgumentIndex) {
        if (arguments.length > pageArgumentIndex + MIN_PAGE_NUMBER) {
            notifyActor(actor, List.of(USAGE));
            return null;
        }
        int pageNumber = MIN_PAGE_NUMBER;
        if (arguments.length > pageArgumentIndex) {
            try {
                pageNumber = Integer.parseInt(arguments[pageArgumentIndex]);
            } catch (NumberFormatException exception) {
                notifyActor(actor, List.of("Page must be a positive whole number."));
                return null;
            }
        }
        if (pageNumber < MIN_PAGE_NUMBER) {
            notifyActor(actor, List.of("Page must be a positive whole number."));
            return null;
        }
        int pageSize = currentPageSize();
        try {
            int offset = Math.multiplyExact(pageNumber - MIN_PAGE_NUMBER, pageSize);
            return new PageRequest(offset, pageSize);
        } catch (ArithmeticException exception) {
            notifyActor(actor, List.of("That page number is too large."));
            return null;
        }
    }

    private boolean beginQuery(CommandActor actor) {
        if (!queryCapacity.tryAcquire()) {
            return false;
        }
        if (!activeActors.add(actor)) {
            queryCapacity.release();
            return false;
        }
        return true;
    }

    private void finishQuery(CommandActor actor) {
        if (activeActors.remove(actor)) {
            queryCapacity.release();
        }
    }

    private int currentPageSize() {
        int pageSize = pageSizeSupplier.getAsInt();
        validatePageSize(pageSize);
        return pageSize;
    }

    private static void validatePageSize(int pageSize) {
        if (pageSize < MIN_PAGE_NUMBER || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside supported bounds");
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
            lines.add("More results are available on page " + (pageNumber(page) + MIN_PAGE_NUMBER) + '.');
        }
        return lines;
    }

    private static List<String> auditLines(LoreInstanceId instanceId, AuditView view) {
        List<String> lines = new ArrayList<>();
        lines.add("Lore-item evidence for " + instanceId.value());
        appendCurrentState(lines, view.currentState());
        appendObservationLines(lines, view.observations());
        appendAnomalyLines(lines, view.anomalies());
        appendAuditLines(lines, view.audit());
        if (hasNoEvidence(view)) {
            lines.add("No current state, location, anomaly, or audit evidence was found.");
        } else if (hasMoreEvidence(view)) {
            lines.add("More evidence is available on the next page.");
        }
        return lines;
    }

    private static void appendObservationLines(
            List<String> lines,
            Page<InstanceObservation> observations) {
        for (InstanceObservation observation : observations.items()) {
            lines.add("OBSERVATION " + observation.observationId()
                    + " " + observation.confidence().name()
                    + " at=" + formatLocation(observation.location())
                    + " source=" + observation.source()
                    + " observed=" + Instant.ofEpochMilli(observation.observedAtEpochMillis()));
        }
    }

    private static void appendAnomalyLines(
            List<String> lines,
            Page<InstanceAnomaly> anomalies) {
        for (InstanceAnomaly anomaly : anomalies.items()) {
            lines.add("ANOMALY " + anomaly.type().name() + " " + anomaly.status().name()
                    + " id=" + anomaly.anomalyId()
                    + " detail=" + summarize(anomaly.detail()));
        }
    }

    private static void appendAuditLines(
            List<String> lines,
            Page<AuditEventRecord> audit) {
        for (AuditEventRecord event : audit.items()) {
            lines.add("AUDIT " + Instant.ofEpochMilli(event.occurredAtEpochMillis())
                    + " " + event.eventType()
                    + " actor=" + event.actorType() + ':' + safeActor(event.actorId())
                    + " detail=" + summarize(event.detailJson()));
        }
    }

    private static boolean hasNoEvidence(AuditView view) {
        return view.currentState().isEmpty()
                && view.observations().items().isEmpty()
                && view.anomalies().items().isEmpty()
                && view.audit().items().isEmpty();
    }

    private static boolean hasMoreEvidence(AuditView view) {
        return view.observations().hasMore()
                || view.audit().hasMore()
                || view.anomalies().hasMore();
    }

    private static void appendCurrentState(
            List<String> lines,
            Optional<InstanceCurrentState> currentState) {
        currentState.ifPresent(state -> {
            String location = state.location() == null
                    ? "none"
                    : formatLocation(state.location());
            lines.add("STATE " + state.state().name()
                    + " revision=" + state.stateRevision()
                    + " at=" + location
                    + " updated=" + Instant.ofEpochMilli(state.updatedAtEpochMillis()));
        });
    }

    private static String formatLocation(LocationDescriptor location) {
        String path = location.containerPath() == null
                ? ""
                : ":" + location.containerPath();
        return summarize(location.type().name() + ':' + location.locationKey() + path);
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
        return page.offset() / page.limit() + MIN_PAGE_NUMBER;
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

    private record AuditRequest(LoreInstanceId instanceId, PageRequest page) {}

    private record StateEvidence(
            Optional<InstanceCurrentState> currentState,
            Page<InstanceObservation> observations) {}

    private record HistoryEvidence(
            Page<InstanceAnomaly> anomalies,
            Page<AuditEventRecord> audit) {}

    private record AuditView(
            Optional<InstanceCurrentState> currentState,
            Page<InstanceObservation> observations,
            Page<InstanceAnomaly> anomalies,
            Page<AuditEventRecord> audit) {}
}
