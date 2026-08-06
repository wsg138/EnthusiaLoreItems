package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread-only draft session. */
@SuppressWarnings({"PMD.NullAssignment"})
final class PaperTemplateEditorSession {
    enum State {
        EDITING,
        AWAITING_CHAT,
        PREVIEW,
        CONFIRMING,
        CLOSED
    }

    final UUID sessionId = UUID.randomUUID();
    final UUID confirmationId = UUID.randomUUID();
    final UUID playerId;
    final TemplateManagementSnapshot snapshot;
    final ItemStack before;
    final int returnPage;
    ItemStack draft;
    String pendingAction;
    State state = State.EDITING;
    BukkitTask timeoutTask;

    PaperTemplateEditorSession(
            UUID playerId,
            TemplateManagementSnapshot snapshot,
            ItemStack before,
            ItemStack draft,
            int returnPage) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.before = Objects.requireNonNull(before, "before").clone();
        this.draft = Objects.requireNonNull(draft, "draft").clone();
        this.returnPage = returnPage;
    }

    boolean matches(UUID viewSessionId) {
        return state != State.CLOSED && sessionId.equals(viewSessionId);
    }

    void close() {
        state = State.CLOSED;
        pendingAction = null;
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }
}
