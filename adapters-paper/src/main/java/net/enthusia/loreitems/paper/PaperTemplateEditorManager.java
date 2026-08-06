package net.enthusia.loreitems.paper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.TemplateEditorDraft;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutRequest;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.application.TemplateRevisionStartStatus;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/** Bounded GUI/chat draft lifecycle with explicit preview and durable confirmation. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidLiteralsInIfCondition", "PMD.CyclomaticComplexity", "PMD.NullAssignment"})
public final class PaperTemplateEditorManager implements AutoCloseable {
    public static final String EDIT_PERMISSION = "enthusia.loreitems.admin.edit";

    private static final int MAX_SESSIONS = 32;
    private static final long SESSION_TIMEOUT_TICKS = 20L * 60L * 5L;
    private static final String UNAVAILABLE =
            "Template editing is unavailable while durable storage is read-only or initializing.";

    private final Plugin plugin;
    private final IntSupplier batchLimitSupplier;
    private final Runnable rolloutWake;
    private final long sessionTimeoutTicks;
    private final PaperTemplateEditorRenderer renderer = new PaperTemplateEditorRenderer();
    private final PaperTemplateDraftEditor draftEditor = new PaperTemplateDraftEditor();
    private final PaperItemTemplateCodec templateCodec = new PaperItemTemplateCodec();
    private final Map<UUID, PaperTemplateEditorSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> pendingChatSessions = new ConcurrentHashMap<>();
    private final PaperTemplateManagementLoader managementLoader;
    private final PaperTemplateEditorEvents events;

    private InstanceNavigator instanceNavigator = (playerId, definitionId, page) -> {};
    private DefinitionNavigator definitionNavigator = (playerId, page) -> {};
    private boolean closed;

    public PaperTemplateEditorManager(
            Plugin plugin, IntSupplier batchLimitSupplier, Runnable rolloutWake) {
        this(plugin, batchLimitSupplier, rolloutWake, SESSION_TIMEOUT_TICKS);
    }

    PaperTemplateEditorManager(
            Plugin plugin,
            IntSupplier batchLimitSupplier,
            Runnable rolloutWake,
            long sessionTimeoutTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.batchLimitSupplier = Objects.requireNonNull(batchLimitSupplier, "batchLimitSupplier");
        this.rolloutWake = Objects.requireNonNull(rolloutWake, "rolloutWake");
        if (sessionTimeoutTicks < 1L) {
            throw new IllegalArgumentException("sessionTimeoutTicks must be positive");
        }
        this.sessionTimeoutTicks = sessionTimeoutTicks;
        this.managementLoader = new PaperTemplateManagementLoader(
                plugin, renderer, templateCodec, this::handleFailure, this::runMain);
        this.events = new PaperTemplateEditorEvents(this);
        requireBatchLimit();
        plugin.getServer().getPluginManager().registerEvents(events, plugin);
    }

    void setInstanceNavigator(InstanceNavigator navigator) {
        this.instanceNavigator = Objects.requireNonNull(navigator, "navigator");
    }

    void setDefinitionNavigator(DefinitionNavigator navigator) {
        this.definitionNavigator = Objects.requireNonNull(navigator, "navigator");
    }

    public void openManagement(UUID playerId, LoreDefinitionId definitionId, int returnPage) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(definitionId, "definitionId");
        runMain(() -> openManagementMain(playerId, definitionId, returnPage));
    }

    public void onClick(InventoryClickEvent event) {
        events.onClick(event);
    }

    void onQuit(PlayerQuitEvent event) {
        events.onQuit(event);
    }

    UUID chatSessionId(UUID playerId) {
        return pendingChatSessions.get(Objects.requireNonNull(playerId, "playerId"));
    }

    void receiveChatAsync(UUID playerId, UUID sessionId, String message) {
        runMain(() -> receiveChat(playerId, sessionId, message));
    }

    void handleQuit(UUID playerId) {
        PaperTemplateEditorSession session = sessions.remove(playerId);
        if (session != null) {
            pendingChatSessions.remove(session.playerId);
            session.close();
        }
    }

    void closeSessions(String reason) {
        Objects.requireNonNull(reason, "reason");
        runMain(() -> {
            for (PaperTemplateEditorSession session : sessions.values()) {
                Player player = Bukkit.getPlayer(session.playerId);
                if (player != null) {
                    player.sendMessage("Template draft cancelled: " + reason);
                    org.bukkit.inventory.Inventory topInventory =
                            player.getOpenInventory().getTopInventory();
                    if (topInventory != null
                            && topInventory.getHolder() instanceof PaperTemplateEditorView) {
                        player.closeInventory();
                    }
                }
                session.close();
            }
            sessions.clear();
            pendingChatSessions.clear();
        });
    }

    private void openManagementMain(
            UUID playerId, LoreDefinitionId definitionId, int returnPage) {
        if (closed) {
            message(playerId, UNAVAILABLE);
            return;
        }
        managementLoader.open(playerId, definitionId, returnPage);
    }

    void dispatchClick(Player player, PaperTemplateEditorView view, int slot) {
        if (!LoreItemsAdministrationCommandExecutor.canBrowse(player)) {
            player.sendMessage("You do not have permission to browse lore-item templates.");
            return;
        }
        switch (view.screen) {
            case MANAGEMENT -> clickManagement(player, view, slot);
            case EDITOR -> clickEditor(player, view, slot);
            case PREVIEW -> clickPreview(player, view, slot);
            default -> throw new IllegalStateException("Unknown template editor screen");
        }
    }

    private void clickManagement(Player player, PaperTemplateEditorView view, int slot) {
        if (slot == PaperTemplateEditorRenderer.MANAGEMENT_BACK) {
            definitionNavigator.open(player.getUniqueId(), view.returnPage);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_REFRESH) {
            openManagementMain(player.getUniqueId(), view.definitionId(), view.returnPage);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_INSTANCES) {
            instanceNavigator.open(player.getUniqueId(), view.definitionId(), 1);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_EDIT) {
            beginEdit(player, view, false);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_REPLACE) {
            beginEdit(player, view, true);
        }
    }

    private void beginEdit(Player player, PaperTemplateEditorView view, boolean replaceHeld) {
        if (!player.hasPermission(EDIT_PERMISSION)) {
            player.sendMessage("You do not have permission to edit lore-item templates.");
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage("You already have an active template draft; cancel it first.");
            return;
        }
        if (sessions.size() >= MAX_SESSIONS) {
            player.sendMessage("The bounded template-editor session limit is currently full.");
            return;
        }
        try {
            ItemStack before = templateCodec.decode(view.snapshot.currentTemplate());
            ItemStack draft = replaceHeld ? normalizedHeld(player) : before.clone();
            PaperTemplateEditorSession session = new PaperTemplateEditorSession(
                    player.getUniqueId(), view.snapshot, before, draft, view.returnPage);
            sessions.put(player.getUniqueId(), session);
            resetTimeout(session);
            if (replaceHeld) {
                session.state = PaperTemplateEditorSession.State.PREVIEW;
                renderer.showPreview(player, session);
            } else {
                renderer.showEditor(player, session);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(exception.getMessage());
        } catch (RuntimeException exception) {
            handleFailure(player.getUniqueId(), "begin template draft", exception);
        }
    }

    private ItemStack normalizedHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            throw new IllegalArgumentException("Hold a non-air item to replace the template.");
        }
        EncodedItemTemplate encoded = templateCodec.encode(held.clone());
        return templateCodec.decode(encoded);
    }

    private void clickEditor(Player player, PaperTemplateEditorView view, int slot) {
        PaperTemplateEditorSession session = currentSession(player, view);
        if (session == null || session.state == PaperTemplateEditorSession.State.CONFIRMING) {
            return;
        }
        if (slot == PaperTemplateEditorRenderer.EDITOR_CANCEL) {
            cancelSession(player, session, "Draft cancelled; the template was not changed.", true);
            return;
        }
        if (slot == PaperTemplateEditorRenderer.EDITOR_PREVIEW) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            resetTimeout(session);
            renderer.showPreview(player, session);
            return;
        }
        PaperTemplateEditorRenderer.ActionSpec action = PaperTemplateEditorRenderer.action(slot);
        if (action != null) {
            startPrompt(player, session, action);
        }
    }

    private void startPrompt(
            Player player,
            PaperTemplateEditorSession session,
            PaperTemplateEditorRenderer.ActionSpec action) {
        session.pendingAction = action.action();
        session.state = PaperTemplateEditorSession.State.AWAITING_CHAT;
        pendingChatSessions.put(player.getUniqueId(), session.sessionId);
        resetTimeout(session);
        player.closeInventory();
        player.sendMessage(action.title() + ": " + String.join(" ", action.help()));
        player.sendMessage("Type submit <value> to apply this draft edit, or type cancel.");
    }

    void receiveChat(UUID playerId, String message) {
        UUID sessionId = chatSessionId(playerId);
        if (sessionId != null) {
            receiveChat(playerId, sessionId, message);
        }
    }

    void receiveChat(UUID playerId, UUID sessionId, String message) {
        PaperTemplateEditorSession session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null || !session.sessionId.equals(sessionId)
                || session.state != PaperTemplateEditorSession.State.AWAITING_CHAT) {
            pendingChatSessions.remove(playerId, sessionId);
            return;
        }
        if (!player.hasPermission(EDIT_PERMISSION)) {
            cancelSession(player, session, "Permission was removed; draft cancelled.", false);
            return;
        }
        String normalized = message.strip();
        if (normalized.equalsIgnoreCase("cancel")) {
            pendingChatSessions.remove(playerId);
            session.pendingAction = null;
            session.state = PaperTemplateEditorSession.State.EDITING;
            resetTimeout(session);
            renderer.showEditor(player, session);
            return;
        }
        if (!normalized.regionMatches(true, 0, "submit ", 0, 7)) {
            player.sendMessage("Nothing was applied. Type submit <value>, or cancel.");
            return;
        }
        String input = normalized.substring(7).strip();
        if (input.isEmpty()) {
            player.sendMessage("Nothing was applied. A value is required after submit.");
            return;
        }
        PaperTemplateEditResult result = draftEditor.apply(
                session.draft, session.pendingAction, input);
        if (!result.accepted()) {
            player.sendMessage("Validation failed: " + result.detail());
            player.sendMessage("Correct the value with submit <value>, or type cancel.");
            return;
        }
        session.draft = result.item();
        session.pendingAction = null;
        session.state = PaperTemplateEditorSession.State.EDITING;
        pendingChatSessions.remove(playerId);
        resetTimeout(session);
        player.sendMessage(result.detail());
        renderer.showEditor(player, session);
    }

    private void clickPreview(Player player, PaperTemplateEditorView view, int slot) {
        PaperTemplateEditorSession session = currentSession(player, view);
        if (session == null) {
            return;
        }
        if (slot == PaperTemplateEditorRenderer.PREVIEW_BACK
                && session.state != PaperTemplateEditorSession.State.CONFIRMING) {
            session.state = PaperTemplateEditorSession.State.EDITING;
            resetTimeout(session);
            renderer.showEditor(player, session);
        } else if (slot == PaperTemplateEditorRenderer.PREVIEW_CANCEL
                && session.state != PaperTemplateEditorSession.State.CONFIRMING) {
            cancelSession(player, session, "Draft cancelled; the template was not changed.", true);
        } else if (slot == PaperTemplateEditorRenderer.PREVIEW_CONFIRM) {
            confirm(player, session);
        }
    }

    private void confirm(Player player, PaperTemplateEditorSession session) {
        if (!player.hasPermission(EDIT_PERMISSION)) {
            cancelSession(player, session, "Permission was removed; draft cancelled.", false);
            return;
        }
        if (session.state == PaperTemplateEditorSession.State.CONFIRMING) {
            player.sendMessage("This draft confirmation is already being processed.");
            return;
        }
        TemplateManagementUseCase useCase = resolveUseCase();
        if (useCase == null) {
            player.sendMessage(UNAVAILABLE);
            return;
        }
        final TemplateRevisionRolloutRequest request;
        try {
            TemplateEditorDraft draft = TemplateEditorDraft.begin(
                    session.confirmationId,
                    session.snapshot.definition().id(),
                    session.snapshot.definition().currentRevision(),
                    templateCodec.encode(session.before),
                    player.getUniqueId()).withTemplate(templateCodec.encode(session.draft));
            request = draft.confirm(requireBatchLimit());
        } catch (IllegalArgumentException exception) {
            player.sendMessage("Confirmation rejected: " + exception.getMessage());
            return;
        }
        session.state = PaperTemplateEditorSession.State.CONFIRMING;
        resetTimeout(session);
        CompletionStage<TemplateRevisionStartResult> stage;
        try {
            stage = Objects.requireNonNull(useCase.confirm(request), "template confirmation stage");
        } catch (RuntimeException exception) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            handleFailure(player.getUniqueId(), "confirm template revision", exception);
            return;
        }
        stage.whenComplete((result, failure) -> runMain(() ->
                finishConfirmation(player.getUniqueId(), session.sessionId, result, failure)));
    }

    private void finishConfirmation(
            UUID playerId,
            UUID sessionId,
            TemplateRevisionStartResult result,
            Throwable failure) {
        PaperTemplateEditorSession session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || !session.sessionId.equals(sessionId) || player == null) {
            return;
        }
        if (failure != null) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            resetTimeout(session);
            handleFailure(playerId, "confirm template revision", failure);
            return;
        }
        if (result == null) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            player.sendMessage("Confirmation returned no result; no completion was claimed.");
            return;
        }
        if (result.status() == TemplateRevisionStartStatus.STARTED
                || result.status() == TemplateRevisionStartStatus.ALREADY_STARTED) {
            sessions.remove(playerId);
            pendingChatSessions.remove(playerId);
            session.close();
            player.closeInventory();
            player.sendMessage("Template revision " + result.currentRevision().value()
                    + " is durable; rollout work is queued.");
            rolloutWake.run();
            openManagementMain(playerId, session.snapshot.definition().id(), session.returnPage);
            return;
        }
        sessions.remove(playerId);
        pendingChatSessions.remove(playerId);
        session.close();
        player.closeInventory();
        player.sendMessage("Template confirmation was not applied: " + result.status());
        openManagementMain(playerId, session.snapshot.definition().id(), session.returnPage);
    }

    private PaperTemplateEditorSession currentSession(
            Player player, PaperTemplateEditorView view) {
        PaperTemplateEditorSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.matches(view.sessionId)) {
            player.sendMessage("That template-editor screen is stale; no edit was applied.");
            return null;
        }
        resetTimeout(session);
        return session;
    }

    private void cancelSession(
            Player player,
            PaperTemplateEditorSession session,
            String detail,
            boolean reopenManagement) {
        sessions.remove(player.getUniqueId());
        pendingChatSessions.remove(player.getUniqueId());
        session.close();
        player.closeInventory();
        player.sendMessage(detail);
        if (reopenManagement) {
            openManagementMain(
                    player.getUniqueId(),
                    session.snapshot.definition().id(),
                    session.returnPage);
        }
    }

    private void resetTimeout(PaperTemplateEditorSession session) {
        if (session.timeoutTask != null) {
            session.timeoutTask.cancel();
        }
        session.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> timeout(session.playerId, session.sessionId),
                sessionTimeoutTicks);
    }

    private void timeout(UUID playerId, UUID sessionId) {
        PaperTemplateEditorSession session = sessions.get(playerId);
        if (session == null || !session.sessionId.equals(sessionId)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        boolean confirmationInFlight =
                session.state == PaperTemplateEditorSession.State.CONFIRMING;
        sessions.remove(playerId);
        pendingChatSessions.remove(playerId);
        session.close();
        if (player != null) {
            player.closeInventory();
            player.sendMessage(confirmationInFlight
                    ? "Template confirmation is still processing; reopen management to check durable status."
                    : "Template draft timed out; no revision was created.");
        }
    }

    int activeSessionCount() {
        return sessions.size();
    }

    boolean awaitingChat(UUID playerId) {
        return pendingChatSessions.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    PaperTemplateEditorSession.State sessionState(UUID playerId) {
        PaperTemplateEditorSession session = sessions.get(
                Objects.requireNonNull(playerId, "playerId"));
        return session == null ? null : session.state;
    }

    private TemplateManagementUseCase resolveUseCase() {
        return plugin.getServer().getServicesManager().load(TemplateManagementUseCase.class);
    }

    private int requireBatchLimit() {
        int value = batchLimitSupplier.getAsInt();
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("Template rollout batch limit must be 1-100");
        }
        return value;
    }

    private void handleFailure(UUID playerId, String operation, Throwable throwable) {
        Throwable failure = unwrap(throwable);
        plugin.getLogger().log(Level.SEVERE, "Could not " + operation + '.', failure);
        message(playerId, "Could not " + operation + "; no unconfirmed edit was persisted.");
    }

    private void message(UUID playerId, String detail) {
        runMain(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(detail);
            }
        });
    }

    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE, "Could not schedule template-editor work during shutdown.", exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException exception && exception.getCause() != null
                ? exception.getCause()
                : throwable;
    }

    @Override
    public void close() {
        closed = true;
        closeSessions("plugin shutdown");
    }

    @FunctionalInterface
    interface DefinitionNavigator {
        void open(UUID playerId, int page);
    }

    @FunctionalInterface
    interface InstanceNavigator {
        void open(UUID playerId, LoreDefinitionId definitionId, int page);
    }
}
