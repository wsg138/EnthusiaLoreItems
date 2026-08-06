package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchResult;
import net.enthusia.loreitems.application.TemplateRevisionRolloutRequest;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateEditorManagerTest {
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2);

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private RecordingUseCase useCase;
    private AtomicInteger rolloutWakes;
    private PaperTemplateEditorManager manager;
    private TemplateManagementSnapshot snapshot;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        player.addAttachment(
                plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);
        player.addAttachment(plugin, PaperTemplateEditorManager.EDIT_PERMISSION, true);
        snapshot = snapshot(ItemStack.of(Material.PAPER));
        useCase = new RecordingUseCase(snapshot);
        server.getServicesManager().register(
                TemplateManagementUseCase.class,
                useCase,
                plugin,
                ServicePriority.Normal);
        rolloutWakes = new AtomicInteger();
        manager = new PaperTemplateEditorManager(plugin, () -> 8, rolloutWakes::incrementAndGet, 3L);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        MockBukkit.unmock();
    }

    @Test
    void invalidInputCancelReloadDisconnectAndTimeoutCreateNoRevision() {
        openEditor();
        click(10);
        assertTrue(manager.awaitingChat(player.getUniqueId()));
        manager.receiveChat(player.getUniqueId(), "submit missing-style");
        assertTrue(manager.awaitingChat(player.getUniqueId()));
        assertEquals(PaperTemplateEditorSession.State.AWAITING_CHAT,
                manager.sessionState(player.getUniqueId()));
        manager.receiveChat(player.getUniqueId(), "cancel");
        assertFalse(manager.awaitingChat(player.getUniqueId()));
        assertEquals(PaperTemplateEditorSession.State.EDITING,
                manager.sessionState(player.getUniqueId()));
        assertEquals(0, useCase.confirmCalls);

        manager.closeSessions("configuration reload");
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        openEditor();
        manager.onQuit(new PlayerQuitEvent(
                player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        openEditor();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);
    }

    @Test
    void duplicateConfirmationClicksCreateOneLogicalRequestAndWakeOneRollout() {
        openEditor();
        click(10);
        manager.receiveChat(player.getUniqueId(), "submit literal Edited name");
        click(PaperTemplateEditorRenderer.EDITOR_PREVIEW);
        assertEquals(PaperTemplateEditorSession.State.PREVIEW,
                manager.sessionState(player.getUniqueId()));

        click(PaperTemplateEditorRenderer.PREVIEW_CONFIRM);
        click(PaperTemplateEditorRenderer.PREVIEW_CONFIRM);

        assertEquals(1, useCase.confirmCalls);
        assertNotNull(useCase.request);
        assertEquals(REVISION_ONE, useCase.request.expectedCurrentRevision());
        assertFalse(useCase.request.beforeTemplate().equals(useCase.request.template()));
        assertEquals(PaperTemplateEditorSession.State.CONFIRMING,
                manager.sessionState(player.getUniqueId()));

        useCase.confirmation.complete(TemplateRevisionStartResult.started(
                snapshot.definition().id(),
                REVISION_TWO,
                TemplateRevisionRolloutBatchResult.complete(0)));

        assertEquals(0, manager.activeSessionCount());
        assertEquals(1, rolloutWakes.get());
    }

    @Test
    void unchangedDraftCannotCreateARevision() {
        openEditor();
        click(10);
        manager.receiveChat(player.getUniqueId(), "submit clear");
        click(PaperTemplateEditorRenderer.EDITOR_PREVIEW);
        click(PaperTemplateEditorRenderer.PREVIEW_CONFIRM);

        assertEquals(0, useCase.confirmCalls);
        assertEquals(PaperTemplateEditorSession.State.PREVIEW,
                manager.sessionState(player.getUniqueId()));
        assertEquals(0, rolloutWakes.get());
    }

    @Test
    void replaceHeldUsesExactCodecFallbackAndStripsLoreIdentity() {
        PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
        ItemStack held = ItemStack.of(Material.DIAMOND_SWORD, 4);
        ItemMeta meta = held.getItemMeta();
        meta.customName(Component.text("Exact replacement"));
        assertTrue(held.setItemMeta(meta));
        held = identityCodec.writeIdentity(held, new LoreItemIdentity(
                snapshot.definition().id(),
                new LoreInstanceId(UUID.randomUUID()),
                REVISION_ONE));
        player.getInventory().setItemInMainHand(held);
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);

        click(PaperTemplateEditorRenderer.MANAGEMENT_REPLACE);

        PaperTemplateEditorView view = assertInstanceOf(
                PaperTemplateEditorView.class,
                player.getOpenInventory().getTopInventory().getHolder());
        assertEquals(PaperTemplateEditorView.Screen.PREVIEW, view.screen);
        ItemStack draft = player.getOpenInventory().getTopInventory().getItem(15);
        assertNotNull(draft);
        assertEquals(Material.DIAMOND_SWORD, draft.getType());
        assertEquals(1, draft.getAmount());
        assertEquals(1, draft.getItemMeta().getMaxStackSize());
        assertEquals("Exact replacement",
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(draft.getItemMeta().customName()));
        assertInstanceOf(ItemIdentityReadResult.Untracked.class,
                identityCodec.readIdentity(draft));
        assertEquals(0, useCase.confirmCalls);
    }

    @Test
    void permissionAndDegradedServicePathsDoNotOpenDrafts() {
        player.addAttachment(plugin, PaperTemplateEditorManager.EDIT_PERMISSION, false);
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        click(PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        server.getServicesManager().unregister(TemplateManagementUseCase.class, useCase);
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);
    }

    private void openEditor() {
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        click(PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        assertEquals(1, manager.activeSessionCount());
        assertEquals(PaperTemplateEditorSession.State.EDITING,
                manager.sessionState(player.getUniqueId()));
    }

    private void click(int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                rawSlot,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        manager.onClick(event);
        assertTrue(event.isCancelled());
    }

    private TemplateManagementSnapshot snapshot(ItemStack item) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition definition = new LoreDefinition(
                definitionId,
                new DefinitionKey("editor-manager"),
                "Editor Manager",
                REVISION_ONE,
                1L,
                null);
        return new TemplateManagementSnapshot(
                definition,
                new PaperItemTemplateCodec().encode(item),
                2L,
                1L,
                0L);
    }

    private static final class RecordingUseCase implements TemplateManagementUseCase {
        private final TemplateManagementSnapshot snapshot;
        private final CompletableFuture<TemplateRevisionStartResult> confirmation =
                new CompletableFuture<>();
        private TemplateRevisionRolloutRequest request;
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
            this.request = request;
            return confirmation;
        }
    }
}
