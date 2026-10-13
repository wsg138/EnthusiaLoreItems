package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import java.util.UUID;
import java.util.Set;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperBoundedContinuationTest {
    private static final String KEY = "minecraft:overworld:0:70:15";
    private static final String SLOT_ZERO = "slot:0";
    private static final LocationDescriptor.Type TYPE = LocationDescriptor.Type.BLOCK_CONTAINER;
    private static final LoreItemIdentity ID = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));
    private static final LoreItemIdentity SECOND = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
            new TemplateRevision(1));

    private final PaperBoundedInventoryContinuation traversal =
            new PaperBoundedInventoryContinuation();
    private ServerMock server;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    @Test
    void twoHundredFiftySeventhRootIsNotPermanentlyStarved() {
        ItemStack[] roots = new ItemStack[300];
        roots[256] = tracked(ID);
        var first = page(roots, 14L, "single", null);
        assertEquals(PaperBoundedInventoryContinuation.State.PARTIAL, first.state());
        assertEquals(256, first.visited());
        assertTrue(first.observations().isEmpty());
        assertEquals(256, first.nextCursor().path().getFirst().index());
        assertFalse(first.authoritativeEligible());

        var second = page(roots, 14L, "single", first.nextCursor());
        assertTrue(second.complete());
        assertEquals(List.of("slot:256"), paths(second, ID));
        assertFalse(second.authoritativeEligible(),
                "A cross-tick traversal cannot prove absence of a moved copy");
    }

    @Test
    void sevenHundredRootsAdvanceWithoutRepeatedlyScanningSlotZero() {
        ItemStack[] roots = new ItemStack[701];
        roots[700] = tracked(ID);
        var one = page(roots, 17L, "large", null);
        var two = page(roots, 17L, "large", one.nextCursor());
        var three = page(roots, 17L, "large", two.nextCursor());
        assertEquals(256, one.visited());
        assertEquals(256, two.visited());
        assertEquals(189, three.visited());
        assertEquals(512, two.nextCursor().path().getFirst().index());
        assertEquals(List.of("slot:700"), paths(three, ID));
        assertTrue(three.complete());
    }

    @Test
    void denseDoubleChestScansBothHalvesWithoutInventingSingletonProof() {
        Inventory doubleChest = server.createInventory(null, 54);
        for (int i = 0; i < 54; i++) {
            doubleChest.setItem(i, ItemStack.of(Material.STONE));
        }
        doubleChest.setItem(0, tracked(ID));
        doubleChest.setItem(53, tracked(ID));
        var result = traversal.scan(doubleChest, TYPE, KEY, 1, "left+right", null,
                new PaperScanLimit(256));
        assertTrue(result.complete());
        assertNull(result.nextCursor());
        assertEquals(List.of(SLOT_ZERO, "slot:53"), paths(result, ID));
        assertEquals(54, result.visited());
        assertTrue(result.authoritativeEligible());
        assertEquals(2, result.observations().get(ID).size(),
                "Caller must reconcile both copies, never assert unique");
    }

    @Test
    void shulkerInLastDoubleChestSlotCanResumeItsNestedLeaf() {
        Inventory doubleChest = server.createInventory(null, 54);
        doubleChest.setItem(53, shulkerWith(26, tracked(ID)));
        var result = traversal.scan(doubleChest, TYPE, KEY, 1, "two-halves", null,
                new PaperScanLimit(55));
        assertEquals(PaperBoundedInventoryContinuation.State.PARTIAL, result.state());
        assertTrue(result.observations().isEmpty());
        assertEquals(1, result.nextCursor().path().getLast().index());
        var resumed = traversal.scan(doubleChest, TYPE, KEY, 1, "two-halves",
                result.nextCursor(), new PaperScanLimit(256));
        assertTrue(resumed.complete());
        assertEquals(List.of("slot:53/shulker:26"), paths(resumed, ID));
        assertFalse(resumed.authoritativeEligible());
    }

    @Test
    void nestedBundleInsideShulkerPreservesFullPath() {
        ItemStack bundle = ItemStack.of(Material.WHITE_BUNDLE);
        BundleMeta meta = assertInstanceOf(BundleMeta.class, bundle.getItemMeta());
        meta.setItems(List.of(ItemStack.of(Material.STONE), tracked(ID)));
        assertTrue(bundle.setItemMeta(meta));
        ItemStack[] roots = new ItemStack[] {shulkerWith(5, bundle)};
        var result = page(roots, 8, "nested", null);
        assertEquals(List.of("slot:0/shulker:5/bundle:1"), paths(result, ID));
        assertTrue(result.complete());
    }

    @Test
    void crossChunkPairGeometryChangeInvalidatesCursorBeforeReading() {
        ItemStack[] roots = new ItemStack[270];
        roots[269] = tracked(ID);
        var first = page(roots, 3, "pair-left-0|pair-right-16", null);
        var invalid = page(roots, 3, "pair-left-0|pair-right-15", first.nextCursor());
        assertEquals(PaperBoundedInventoryContinuation.State.INVALIDATED, invalid.state());
        assertTrue(invalid.observations().isEmpty());
        assertNull(invalid.nextCursor());
        assertFalse(invalid.authoritativeEligible());
    }

    @Test
    void mutationLoadGenerationChangeOrRestartInvalidatesCursor() {
        ItemStack[] roots = new ItemStack[280];
        var first = page(roots, 9, "pair", null);
        roots[0] = tracked(ID);
        var invalid = page(roots, 10, "pair", first.nextCursor());
        assertEquals(PaperBoundedInventoryContinuation.State.INVALIDATED, invalid.state());
        var fresh = page(roots, 10, "pair", null);
        assertEquals(List.of(SLOT_ZERO), paths(fresh, ID));
        assertFalse(fresh.complete());
    }

    @Test
    void unobservedMutationMayNotYieldAuthoritativeUniqueness() {
        ItemStack[] roots = new ItemStack[260];
        roots[0] = tracked(ID);
        var first = page(roots, 10, "no-guaranteed-fence", null);
        roots[259] = tracked(ID);
        var last = page(roots, 10, "no-guaranteed-fence", first.nextCursor());
        assertTrue(last.complete());
        assertFalse(last.authoritativeEligible());
        assertEquals(List.of("slot:259"), paths(last, ID));
        assertEquals(List.of(SLOT_ZERO), paths(first, ID));
    }

    @Test
    void duplicateOnlyInUnvisitedHalfNeverProducesEarlyTransition() {
        ItemStack[] roots = new ItemStack[54];
        roots[0] = tracked(ID);
        roots[53] = tracked(ID);
        var first = traversal.scanSlots(54, i -> roots[i], TYPE, KEY, 2,
                "double", null, new PaperScanLimit(27));
        assertEquals(List.of(SLOT_ZERO), paths(first, ID));
        assertFalse(first.authoritativeEligible());
        assertNotNull(first.nextCursor());
        var second = page(roots, 2, "double", first.nextCursor());
        assertEquals(List.of("slot:53"), paths(second, ID));
        assertFalse(second.authoritativeEligible());
    }

    @Test
    void ordinaryRootSlotAndSecondTrackedIdentityAreAccountedFor() {
        ItemStack[] roots = new ItemStack[4];
        roots[0] = ItemStack.of(Material.STONE);
        roots[1] = tracked(ID);
        roots[3] = tracked(SECOND);
        var result = traversal.scanSlots(4, i -> roots[i], TYPE, KEY, 2, "one",
                null, new PaperScanLimit(2));
        assertEquals(2, result.visited());
        assertEquals(List.of("slot:1"), paths(result, ID));
        assertTrue(result.observations().getOrDefault(SECOND, List.of()).isEmpty());
        assertFalse(result.authoritativeEligible());
    }

    @Test
    void collectorChargesEmptyRootsRatherThanSkippingToSlotTwoHundredFiftySeven() {
        ItemStack[] roots = new ItemStack[300];
        roots[256] = tracked(ID);
        PaperScanLimit limit = new PaperScanLimit(256);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();

        new PaperTrackedItemCollector().collectArray(
                roots, TYPE, KEY, "slot:", observations, limit);

        assertFalse(limit.hasRemaining());
        assertTrue(observations.isEmpty(),
                "Legacy synchronous collector must not exceed 256 slot visits");
    }

    @Test
    void legacyPhysicalScannerAlsoChargesEmptyTopLevelSlots() {
        var plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase useCase = request -> CompletableFuture.completedFuture(
                TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        PaperTrackingCoordinator coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        try {
            Inventory inventory = server.createInventory(null, 9);
            inventory.setItem(8, tracked(ID));
            PaperScanLimit limit = new PaperScanLimit(4);
            new PaperPhysicalInventoryScanner(coordinator).scanInventory(
                    inventory, TYPE, KEY, TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    "bounded-empty-slots", limit);
            assertFalse(limit.hasRemaining());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void deepNegativeEvidenceProbeTreatsBudgetExhaustionAsUnresolvedHint() {
        ItemStack outer = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta outerMeta = assertInstanceOf(BlockStateMeta.class, outer.getItemMeta());
        ShulkerBox outerBox = assertInstanceOf(ShulkerBox.class, outerMeta.getBlockState());
        for (int i = 0; i < 12; i++) {
            ItemStack inner = ItemStack.of(Material.SHULKER_BOX);
            BlockStateMeta innerMeta = assertInstanceOf(BlockStateMeta.class, inner.getItemMeta());
            ShulkerBox innerBox = assertInstanceOf(ShulkerBox.class,
                    innerMeta.getBlockState());
            for (int slot = 0; slot < 27; slot++) {
                innerBox.getInventory().setItem(slot, ItemStack.of(Material.STONE));
            }
            innerMeta.setBlockState(innerBox);
            assertTrue(inner.setItemMeta(innerMeta));
            outerBox.getInventory().setItem(i, inner);
        }
        outerMeta.setBlockState(outerBox);
        assertTrue(outer.setItemMeta(outerMeta));

        PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
        assertTrue(collector.hasIdentityEvidence(outer),
                "Unknown after 256 visits must trigger reconciliation, never false absence");
        assertTrue(collector.hasNestedIdentityEvidence(outer));
    }

    @Test
    void siblingShulkerSlotsReuseOneMetadataSnapshotPerPage() {
        CountingShulker item = new CountingShulker();
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        for (int slot = 0; slot < 27; slot++) {
            box.getInventory().setItem(slot, ItemStack.of(Material.STONE));
        }
        meta.setBlockState(box);
        assertTrue(item.setItemMeta(meta));
        item.reset();
        ItemStack[] roots = {item};

        var result = page(roots, 20, "cached", null);
        assertTrue(result.complete());
        assertTrue(item.metadataReads() <= 3,
                "Nested siblings must not repeatedly deserialize one shulker root");
        assertEquals(28, result.visited());
    }

    @Test
    void inaccessibleInventoryReturnsUnresolvedRatherThanThrowing() {
        Inventory inaccessible = (Inventory) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Inventory.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("inventory unloaded");
                });
        var page = traversal.scan(inaccessible, TYPE, KEY, 25L, "unloaded",
                null, new PaperScanLimit(4));
        assertEquals(PaperBoundedInventoryContinuation.State.UNRESOLVED, page.state());
        assertTrue(page.observations().isEmpty());
        assertNull(page.nextCursor());
        assertFalse(page.authoritativeEligible());
    }

    @Test
    void legacyIncompleteScanNeverSubmitsRequestedAuthoritativeMode() {
        var plugin = MockBukkit.createMockPlugin();
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "ok"));
        };
        PaperTrackingCoordinator coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        try {
            Inventory inventory = server.createInventory(null, 9);
            inventory.setItem(0, tracked(ID));
            inventory.setItem(8, tracked(ID));
            PaperScanLimit limit = new PaperScanLimit(4);
            new PaperPhysicalInventoryScanner(coordinator).scanInventory(
                    inventory, TYPE, KEY, TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                    "partial-must-reconcile", limit);
            assertFalse(limit.hasRemaining());
            assertEquals(1, observed.size());
            assertEquals(TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    observed.getFirst().mode());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void unreadableIdentityBlocksAuthoritativeCoverage() {
        ItemStack malformed = malformedIdentity();
        var result = page(new ItemStack[] {tracked(SECOND), malformed}, 30,
                "malformed-root", null);
        assertEquals(PaperBoundedInventoryContinuation.State.UNRESOLVED, result.state());
        assertFalse(result.authoritativeEligible());
        assertNull(result.nextCursor());
        assertEquals(List.of(SLOT_ZERO), paths(result, SECOND));
    }

    @Test
    void unreadableNestedIdentityAlsoBlocksAuthoritativeCoverage() {
        ItemStack[] roots = {shulkerWith(26, malformedIdentity())};
        var result = page(roots, 30, "malformed-nested", null);
        assertEquals(PaperBoundedInventoryContinuation.State.UNRESOLVED, result.state());
        assertFalse(result.authoritativeEligible());
        assertNull(result.nextCursor());
    }


    @Test
    void nineDenseShulkersDoNotHideLaterDuplicateWhenPagesAreDrained() {
        ItemStack[] roots = new ItemStack[11];
        roots[0] = tracked(ID);
        for (int slot = 1; slot <= 9; slot++) {
            roots[slot] = filledShulker(-1);
        }
        roots[10] = filledShulker(26);
        PaperBoundedInventoryContinuation.Cursor cursor = null;
        List<String> found = new java.util.ArrayList<>();
        int pages = 0;
        PaperBoundedInventoryContinuation.Page result;
        do {
            result = page(roots, 44L, "dense-nested", cursor);
            found.addAll(paths(result, ID));
            assertTrue(result.visited() <= 256);
            assertFalse(result.authoritativeEligible(),
                    "A full multi-page inventory never proves uniqueness");
            cursor = result.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);
        assertEquals(2, pages);
        assertTrue(result.coverageComplete());
        assertEquals(List.of("slot:0", "slot:10/shulker:26"), found);
    }

    @Test
    void malformedEarlierRootStillAllowsLaterDuplicateToBeObserved() {
        ItemStack[] roots = new ItemStack[271];
        roots[0] = malformedIdentity();
        roots[1] = tracked(ID);
        roots[270] = tracked(ID);
        var first = page(roots, 45L, "malformed-and-late", null);
        assertEquals(PaperBoundedInventoryContinuation.State.PARTIAL, first.state());
        assertTrue(first.malformedEvidence());
        assertEquals(List.of("slot:1"), paths(first, ID));
        var last = page(roots, 45L, "malformed-and-late", first.nextCursor());
        assertEquals(PaperBoundedInventoryContinuation.State.UNRESOLVED, last.state());
        assertEquals(List.of("slot:270"), paths(last, ID));
        assertFalse(last.coverageComplete());
    }

    @Test
    void explicitMutationFenceInvalidatesPriorPage() {
        ItemStack[] roots = new ItemStack[280];
        var first = traversal.scanSlots(roots.length, i -> roots[i], TYPE, KEY,
                47L, 100L, "two-loaded-halves", null, new PaperScanLimit(256));
        assertEquals(PaperBoundedInventoryContinuation.State.PARTIAL, first.state());
        roots[0] = tracked(ID);
        var stale = traversal.scanSlots(roots.length, i -> roots[i], TYPE, KEY,
                47L, 101L, "two-loaded-halves",
                first.nextCursor(), new PaperScanLimit(256));
        assertEquals(PaperBoundedInventoryContinuation.State.INVALIDATED, stale.state());
        assertTrue(stale.observations().isEmpty());
        var restart = traversal.scanSlots(roots.length, i -> roots[i], TYPE, KEY,
                47L, 101L, "two-loaded-halves", null, new PaperScanLimit(256));
        assertEquals(List.of(SLOT_ZERO), paths(restart, ID));
    }

    @Test
    void malformedOrPartialIdentityProbeHasExplicitUnknownCoverage() {
        var plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase useCase = request -> CompletableFuture.completedFuture(
                TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        PaperTrackingCoordinator coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        try {
            PaperPhysicalInventoryScanner scanner = new PaperPhysicalInventoryScanner(coordinator);
            var incomplete = scanner.inspectIdentities(filledShulkerTree());
            assertFalse(incomplete.coverageComplete());
            assertTrue(incomplete.identities().isEmpty());
            var malformed = scanner.inspectIdentities(malformedIdentity());
            assertFalse(malformed.coverageComplete());
            var found = scanner.inspectIdentities(tracked(ID));
            assertTrue(found.coverageComplete());
            assertEquals(Set.of(ID), found.identities());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void scannerSubmitsCopiesFromSeparatePagesOnlyAsReconciliation() {
        var plugin = MockBukkit.createMockPlugin();
        List<TrackingObservationUseCase.Request> recorded = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = request -> {
            recorded.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "ok"));
        };
        PaperTrackingCoordinator coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        try {
            PaperPhysicalInventoryScanner scanner = new PaperPhysicalInventoryScanner(coordinator);
            Inventory inventory = server.createInventory(null, 18);
            inventory.setItem(0, tracked(ID));
            for (int slot = 1; slot <= 9; slot++) {
                inventory.setItem(slot, filledShulker(-1));
            }
            inventory.setItem(10, filledShulker(26));
            PaperBoundedInventoryContinuation.Cursor cursor = null;
            for (int page = 0; page < 4; page++) {
                var result = scanner.scanBounded(
                        inventory, TYPE, KEY, 77L, 9L, "stable-loaded-single",
                        cursor, new PaperScanLimit(256));
                scanner.submitPageObservations(result, "paged-reconciliation");
                cursor = result.nextCursor();
                if (cursor == null) break;
            }
            assertEquals(2, recorded.size());
            assertEquals(2, recorded.stream().filter(
                    r -> r.identity().equals(ID)).count());
            assertTrue(recorded.stream().allMatch(r -> r.mode()
                    == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        } finally {
            coordinator.close();
        }
    }

    private static ItemStack filledShulkerTree() {
        ItemStack root = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, root.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        for (int i = 0; i < 12; i++) box.getInventory().setItem(i, filledShulker(-1));
        meta.setBlockState(box);
        assertTrue(root.setItemMeta(meta));
        return root;
    }

    private static ItemStack filledShulker(int trackedSlot) {
        ItemStack root = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, root.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        for (int i = 0; i < 27; i++) box.getInventory().setItem(
                i, i == trackedSlot ? tracked(ID) : ItemStack.of(Material.STONE));
        meta.setBlockState(box);
        assertTrue(root.setItemMeta(meta));
        return root;
    }


    @Test
    void pagedPlayerCursorAndOpenContainerFindLateCopyWithExactPaths() {
        var player = server.addPlayer("PagedAccessTest");
        player.getInventory().setItem(0, tracked(ID));
        player.setItemOnCursor(tracked(ID));
        Inventory chest = server.createInventory(null, 18);
        for (int slot = 0; slot < 9; slot++) {
            chest.setItem(slot, filledShulker(-1));
        }
        chest.setItem(9, filledShulker(26));
        PaperPagedInventoryAccess pager = new PaperPagedInventoryAccess();
        PaperPagedInventoryAccess.Cursor cursor = null;
        List<String> locations = new java.util.ArrayList<>();
        int pages = 0;
        PaperPagedInventoryAccess.Batch batch;
        do {
            List<PaperPagedInventoryAccess.Source> sources =
                    new java.util.ArrayList<>(PaperPagedInventoryAccess.playerSources(player));
            sources.add(PaperPagedInventoryAccess.containerSource(
                    chest, TYPE, KEY, "loaded-chest"));
            batch = pager.scan(sources, 101L, 9L, cursor, new PaperScanLimit(256));
            assertTrue(batch.visited() <= 256);
            batch.observations().getOrDefault(ID, List.of()).stream()
                    .map(LocationDescriptor::containerPath).forEach(locations::add);
            cursor = batch.next();
            pages++;
        } while (cursor != null && pages < 8);
        assertEquals(2, pages);
        assertTrue(batch.coverageComplete());
        assertEquals(List.of("slot:0", "cursor", "slot:9/shulker:26"), locations);
    }

    @Test
    void pagedPlayerAccessRejectsSourceReorderOrDirtyFence() {
        PaperPagedInventoryAccess pager = new PaperPagedInventoryAccess();
        ItemStack[] items = new ItemStack[300];
        var a = new PaperPagedInventoryAccess.Source(
                new PaperPagedInventoryAccess.Shape(TYPE, KEY, "loaded", "slot:", 300),
                i -> items[i]);
        var b = new PaperPagedInventoryAccess.Source(
                new PaperPagedInventoryAccess.Shape(TYPE, KEY + "-other", "loaded", "slot:", 1),
                i -> null);
        var first = pager.scan(List.of(a, b), 1L, 3L, null, new PaperScanLimit(256));
        assertNotNull(first.next());
        var reordered = pager.scan(List.of(b, a), 1L, 3L,
                first.next(), new PaperScanLimit(256));
        assertEquals(PaperBoundedInventoryContinuation.State.INVALIDATED, reordered.state());
        var mutated = pager.scan(List.of(a, b), 1L, 4L,
                first.next(), new PaperScanLimit(256));
        assertEquals(PaperBoundedInventoryContinuation.State.INVALIDATED, mutated.state());
    }

    private static ItemStack malformedIdentity() {
        ItemStack malformed = tracked(ID);
        ItemMeta meta = malformed.getItemMeta();
        meta.getPersistentDataContainer().remove(
                NamespacedKey.fromString("enthusialoreitems:instance_id"));
        assertTrue(malformed.setItemMeta(meta));
        return malformed;
    }

    @Test
    void emptyContainerEvidenceProbeRemainsNegative() {
        ItemStack empty = ItemStack.of(Material.SHULKER_BOX);
        PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
        assertFalse(collector.hasIdentityEvidence(empty));
        assertFalse(collector.hasNestedIdentityEvidence(empty));
    }

    private PaperBoundedInventoryContinuation.Page page(
            ItemStack[] roots, long generation, String geometry,
            PaperBoundedInventoryContinuation.Cursor cursor) {
        return traversal.scanSlots(roots.length, i -> roots[i], TYPE, KEY,
                generation, geometry, cursor, new PaperScanLimit(256));
    }

    private static List<String> paths(
            PaperBoundedInventoryContinuation.Page result, LoreItemIdentity identity) {
        return result.observations().getOrDefault(identity, List.of()).stream()
                .map(LocationDescriptor::containerPath).toList();
    }

    private static ItemStack tracked(LoreItemIdentity identity) {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
    }

    private static final class CountingShulker extends ItemStack {
        private int reads;

        private CountingShulker() {
            super(Material.SHULKER_BOX);
        }

        @Override
        public ItemMeta getItemMeta() {
            reads++;
            return super.getItemMeta();
        }

        private void reset() {
            reads = 0;
        }

        private int metadataReads() {
            return reads;
        }
    }

    private static ItemStack shulkerWith(int slot, ItemStack nested) {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        box.getInventory().setItem(slot, nested);
        meta.setBlockState(box);
        assertTrue(item.setItemMeta(meta));
        return item;
    }
}
