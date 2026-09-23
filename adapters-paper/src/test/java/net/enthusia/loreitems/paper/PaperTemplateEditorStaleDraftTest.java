package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutRequest;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateEditorStaleDraftTest {
    private ServerMock server;
    private Plugin plugin;
    private PlayerMock owner;
    private PermissionAttachment editPermission;
    private RecordingUseCase useCase;
    private PaperTemplateEditorManager manager;
    private TemplateManagementSnapshot snapshot;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        owner = server.addPlayer();
        owner.addAttachment(plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);
        editPermission = owner.addAttachment(plugin);
        editPermission.setPermission(PaperTemplateEditorManager.EDIT_PERMISSION, true);
        snapshot = snapshot();
        useCase = new RecordingUseCase(snapshot);
        server.getServicesManager().register(
                TemplateManagementUseCase.class, useCase, plugin, ServicePriority.Normal);
        manager = new PaperTemplateEditorManager(plugin, () -> 8, () -> {}, 100L);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        MockBukkit.unmock();
    }

    @Test
    void chatPromptDraftSurvivesDisconnectAndCanBeSafelyCancelledAfterReconnect() {
        openPrompt(owner);
        server.getPluginManager().callEvent(new PlayerQuitEvent(
                owner, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertEquals(1, manager.activeSessionCount());
        assertFalse(manager.awaitingChat(owner.getUniqueId()));
        assertEquals(
                PaperTemplateEditorSession.State.AWAITING_CHAT,
                manager.sessionState(owner.getUniqueId()));
        assertEquals(0, useCase.confirmCalls);

        PlayerMock otherAdmin = server.addPlayer();
        otherAdmin.addAttachment(plugin, PaperTemplateEditorManager.EDIT_PERMISSION, true);
        manager.cancelOwnDraft(otherAdmin);
        assertEquals(1, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        manager.cancelOwnDraft(owner);
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        openEditor(owner);
        assertEquals(1, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);
    }

    @Test
    void staleDraftCancellationStillRequiresEditPermission() {
        openPrompt(owner);
        server.getPluginManager().callEvent(new PlayerQuitEvent(
                owner, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        editPermission.setPermission(PaperTemplateEditorManager.EDIT_PERMISSION, false);

        manager.cancelOwnDraft(owner);
        assertEquals(1, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        editPermission.setPermission(PaperTemplateEditorManager.EDIT_PERMISSION, true);
        manager.cancelOwnDraft(owner);
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);
    }

    private void openPrompt(PlayerMock player) {
        openEditor(player);
        click(player, 10);
        assertEquals(
                PaperTemplateEditorSession.State.AWAITING_CHAT,
                manager.sessionState(player.getUniqueId()));
    }

    private void openEditor(PlayerMock player) {
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        click(player, PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        assertEquals(
                PaperTemplateEditorSession.State.EDITING,
                manager.sessionState(player.getUniqueId()));
    }

    private void click(PlayerMock player, int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                rawSlot,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
    }

    private static TemplateManagementSnapshot snapshot() {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition definition = new LoreDefinition(
                definitionId,
                new DefinitionKey("stale-draft"),
                "Stale Draft",
                new TemplateRevision(1),
                1L,
                null);
        return new TemplateManagementSnapshot(
                definition,
                new PaperItemTemplateCodec().encode(ItemStack.of(Material.PAPER)),
                0L,
                0L,
                0L);
    }

    private static final class RecordingUseCase implements TemplateManagementUseCase {
        private final TemplateManagementSnapshot snapshot;
        private int confirmCalls;

        private RecordingUseCase(TemplateManagementSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public CompletionStage<Optional<TemplateManagementSnapshot>> findSnapshot(
                LoreDefinitionId definitionId) {
            return CompletableFuture.completedFuture(
                    snapshot.definition().id().equals(definitionId)
                            ? Optional.of(snapshot)
                            : Optional.empty());
        }

        @Override
        public CompletionStage<TemplateRevisionStartResult> confirm(
                TemplateRevisionRolloutRequest request) {
            confirmCalls++;
            return CompletableFuture.failedFuture(
                    new AssertionError("stale draft cancellation must not confirm a revision"));
        }
    }
}
