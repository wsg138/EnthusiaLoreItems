package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Immutable stale-click fence attached to template-management inventories. */
final class PaperTemplateEditorView implements InventoryHolder {
    final Screen screen;
    final TemplateManagementSnapshot snapshot;
    final UUID sessionId;
    final int returnPage;
    private Inventory inventory;

    private PaperTemplateEditorView(
            Screen screen,
            TemplateManagementSnapshot snapshot,
            UUID sessionId,
            int returnPage) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.sessionId = sessionId;
        this.returnPage = returnPage;
    }

    static PaperTemplateEditorView management(
            TemplateManagementSnapshot snapshot, int returnPage) {
        return new PaperTemplateEditorView(Screen.MANAGEMENT, snapshot, null, returnPage);
    }

    static PaperTemplateEditorView editor(
            TemplateManagementSnapshot snapshot, UUID sessionId, int returnPage) {
        return new PaperTemplateEditorView(
                Screen.EDITOR,
                snapshot,
                Objects.requireNonNull(sessionId, "sessionId"),
                returnPage);
    }

    static PaperTemplateEditorView preview(
            TemplateManagementSnapshot snapshot, UUID sessionId, int returnPage) {
        return new PaperTemplateEditorView(
                Screen.PREVIEW,
                snapshot,
                Objects.requireNonNull(sessionId, "sessionId"),
                returnPage);
    }

    LoreDefinitionId definitionId() {
        return snapshot.definition().id();
    }

    void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("View inventory is already attached");
        }
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        return Objects.requireNonNull(inventory, "View inventory is not attached");
    }

    enum Screen {
        MANAGEMENT,
        EDITOR,
        PREVIEW
    }
}
