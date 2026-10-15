package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLitePhysicalProofReconciliationPlanner.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Disposable synthetic proof inputs only; none of these cases migrates SQLite. */
class SQLitePhysicalProofReconciliationPlannerTest {
    private static final UUID INSTANCE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String OLD = "minecraft:world:15:70:8";
    private static final String SLOT_ZERO = "slot:0";
    private static final String MODERN = "block-v2:" + WORLD + ":15:70:8";

    @Test
    void singleChestNeedsIdentityLineageAndProposesOnlyNewCurrentKey() {
        Decision decision = plan(OLD, unused -> {});
        assertEquals(Outcome.ELIGIBLE, decision.outcome());
        assertEquals(Reason.PHYSICAL_REOBSERVATION_ONLY, decision.reason());
        assertEquals(MODERN, decision.proposedLocationKey());
        assertEquals(SLOT_ZERO, decision.proposedContainerPath());
        assertEquals(decision, plan(OLD, unused -> {})); // pure deterministic retry
    }

    @Test
    void historicalOnlyAndReverseArrivalDoNotProduceAnAlias() {
        assertRefused(MODERN, unused -> {}, Outcome.ABSENT, Reason.NO_CURRENT_LEGACY_REFERENCE);
        assertRefused("root:BLOCK_CONTAINER:" + MODERN,
                unused -> {}, Outcome.ABSENT, Reason.NO_CURRENT_LEGACY_REFERENCE);
        assertEquals(Outcome.ABSENT, SQLitePhysicalProofReconciliationPlanner
                .plan(null, new Fixture().build()).outcome());
    }

    @Test
    void nestedShulkerRequiresSameExactPathAndPreservesRootShape() {
        Fixture fixture = new Fixture();
        fixture.containerPath = "slot:0/shulker:1";
        CurrentEvidence nested = projection("root:BLOCK_CONTAINER:" + OLD,
                LocationDescriptor.Type.NESTED_CONTAINER, fixture.containerPath);
        Decision decision = SQLitePhysicalProofReconciliationPlanner.plan(nested, fixture.build());
        assertEquals(Outcome.ELIGIBLE, decision.outcome());
        assertEquals("root:BLOCK_CONTAINER:" + MODERN, decision.proposedLocationKey());
        assertEquals("slot:0/shulker:1", decision.proposedContainerPath());
        fixture.containerPath = "slot:0/shulker:2";
        assertResult(nested, fixture, Outcome.CONFLICT, Reason.UNVERIFIED_SLOT_PATH);
    }

    @ParameterizedTest
    @MethodSource("unsafeProofs")
    void refusesAbsentAmbiguousStaleOrConflictingEvidence(
            Consumer<Fixture> change, Outcome outcome, Reason reason) {
        assertRefused(OLD, change, outcome, reason);
    }

    private static Stream<Arguments> unsafeProofs() {
        return Stream.of(
                Arguments.of((Consumer<Fixture>) p -> p.matches = 0,
                        Outcome.ABSENT, Reason.NO_MATCHING_ITEM),
                Arguments.of((Consumer<Fixture>) p -> p.matches = 2,
                        Outcome.AMBIGUOUS, Reason.MULTIPLE_MATCHING_ITEMS),
                Arguments.of((Consumer<Fixture>) p -> p.unresolvedWorlds = 2,
                        Outcome.AMBIGUOUS, Reason.WORLD_NAME_COLLISION),
                Arguments.of((Consumer<Fixture>) p -> p.lineage = false,
                        Outcome.AMBIGUOUS, Reason.WORLD_LINEAGE_UNPROVEN),
                Arguments.of((Consumer<Fixture>) p -> p.instanceId = UUID.randomUUID(),
                        Outcome.CONFLICT, Reason.PDC_IDENTITY_MISMATCH),
                Arguments.of((Consumer<Fixture>) p -> p.revision = 5,
                        Outcome.STALE, Reason.STALE_REVISION_OR_RECEIPT),
                Arguments.of((Consumer<Fixture>) p -> p.receipt = 18,
                        Outcome.STALE, Reason.STALE_REVISION_OR_RECEIPT),
                Arguments.of((Consumer<Fixture>) p -> p.generation = 9,
                        Outcome.STALE, Reason.STALE_GENERATION),
                Arguments.of((Consumer<Fixture>) p -> p.dirty = true,
                        Outcome.STALE, Reason.DIRTY_OR_UNSETTLED),
                Arguments.of((Consumer<Fixture>) p -> p.settled = false,
                        Outcome.STALE, Reason.DIRTY_OR_UNSETTLED),
                Arguments.of((Consumer<Fixture>) p -> p.ack = false,
                        Outcome.STALE, Reason.ACK_NOT_DURABLE),
                Arguments.of((Consumer<Fixture>) p -> p.captured = 100,
                        Outcome.STALE, Reason.STALE_PHYSICAL_CAPTURE),
                Arguments.of((Consumer<Fixture>) p -> p.firstLoaded = false,
                        Outcome.AMBIGUOUS, Reason.UNLOADED_OR_PARTIAL_GEOMETRY),
                Arguments.of((Consumer<Fixture>) p -> p.coverage = false,
                        Outcome.AMBIGUOUS, Reason.UNLOADED_OR_PARTIAL_GEOMETRY),
                Arguments.of((Consumer<Fixture>) p -> p.geometryVerified = false,
                        Outcome.AMBIGUOUS, Reason.UNLOADED_OR_PARTIAL_GEOMETRY),
                Arguments.of((Consumer<Fixture>) p -> p.slot = 27,
                        Outcome.CONFLICT, Reason.INVALID_HOLDER_SLOT),
                Arguments.of((Consumer<Fixture>) p -> p.first = new Position(16, 70, 8),
                        Outcome.CONFLICT, Reason.LOCATION_OR_NESTED_PATH_MISMATCH),
                Arguments.of((Consumer<Fixture>) p -> p.containerPath = "slot:7",
                        Outcome.CONFLICT, Reason.UNVERIFIED_SLOT_PATH));
    }

    @Test
    void rejectsConflictAndTerminalProjectionsEvenWithRealPhysicalItem() {
        CurrentEvidence conflict = projection(OLD, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO,
                InstanceCurrentState.State.CONFLICTING, true);
        assertResult(conflict, new Fixture(), Outcome.CONFLICT, Reason.ACTIVE_CONFLICT);
        CurrentEvidence storedConflict = projection("conflict:unresolved",
                LocationDescriptor.Type.DUPLICATE_CONFLICT, SLOT_ZERO,
                InstanceCurrentState.State.CONFLICTING, true);
        assertResult(storedConflict, new Fixture(), Outcome.CONFLICT, Reason.ACTIVE_CONFLICT);
        CurrentEvidence terminal = projection(OLD, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO,
                InstanceCurrentState.State.TERMINAL_VOID, false);
        assertResult(terminal, new Fixture(), Outcome.CONFLICT, Reason.UNEXPECTED_CURRENT_STATE);
    }

    @Test
    void lastConfirmedIsNotAutomaticallyPromotedToConfirmedNow() {
        CurrentEvidence last = projection(OLD, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO,
                InstanceCurrentState.State.LAST_CONFIRMED, false);
        Decision decision = SQLitePhysicalProofReconciliationPlanner.plan(last, new Fixture().build());
        assertEquals(Outcome.ELIGIBLE, decision.outcome());
        assertEquals(InstanceCurrentState.State.LAST_CONFIRMED, last.state());
        assertEquals(OLD, last.location().locationKey());
    }

    @ParameterizedTest
    @MethodSource("doubleChestCases")
    void doubleChestRequiresVerifiedHalfAndPhysicalSlot(
            Position first, Position second, Position left, String legacy,
            int legacyLocalSlot, int combinedSlot, String expectedKey) {
        Fixture f = new Fixture();
        f.holder = Holder.DOUBLE_CHEST;
        f.first = first;
        f.second = second;
        f.left = left;
        f.slot = combinedSlot;
        f.containerPath = "slot:" + combinedSlot;
        Decision decision = SQLitePhysicalProofReconciliationPlanner.plan(
                projection(legacy, LocationDescriptor.Type.BLOCK_CONTAINER,
                        "slot:" + legacyLocalSlot), f.build());
        assertEquals(Outcome.ELIGIBLE, decision.outcome());
        assertEquals(expectedKey, decision.proposedLocationKey());
        assertEquals("slot:" + combinedSlot, decision.proposedContainerPath());
    }

    private static Stream<Arguments> doubleChestCases() {
        Position a = new Position(15, 70, 8), b = new Position(16, 70, 8);
        Position negA = new Position(-17, 70, 8), negB = new Position(-16, 70, 8);
        Position zA = new Position(8, 70, -1), zB = new Position(8, 70, 0);
        return Stream.of(
                Arguments.of(a, b, a, OLD, 0, 0, paired(a, b, 0)),
                Arguments.of(a, b, a, OLD, 26, 26, paired(a, b, 0)),
                Arguments.of(a, b, a, "minecraft:world:16:70:8", 0, 27, paired(a, b, 0)),
                Arguments.of(a, b, a, "minecraft:world:16:70:8", 26, 53, paired(a, b, 0)),
                Arguments.of(a, b, b, "minecraft:world:16:70:8", 0, 0, paired(a, b, 1)),
                Arguments.of(a, b, b, OLD, 26, 53, paired(a, b, 1)),
                Arguments.of(negA, negB, negB, "minecraft:world:-17:70:8",
                        26, 53, paired(negA, negB, 1)),
                Arguments.of(zA, zB, zA, "minecraft:world:8:70:0",
                        0, 27, paired(zA, zB, 0)));
    }

    @Test
    void missingHalfSplitRejoinOrBadGeometryNeverClaimsFull54() {
        Fixture missing = pairFixture();
        missing.secondLoaded = false;
        assertRefused(OLD, f -> copyPair(f, missing), Outcome.AMBIGUOUS,
                Reason.UNLOADED_OR_PARTIAL_GEOMETRY);
        Fixture split = pairFixture();
        split.second = new Position(17, 70, 8);
        assertRefused(OLD, f -> copyPair(f, split), Outcome.CONFLICT,
                Reason.UNVERIFIED_CHEST_GEOMETRY);
        Fixture rejoined = pairFixture();
        rejoined.left = rejoined.second;
        rejoined.slot = 27;
        rejoined.containerPath = "slot:27";
        assertEquals(Outcome.ELIGIBLE,
                SQLitePhysicalProofReconciliationPlanner.plan(
                        projection(OLD, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO),
                        rejoined.build()).outcome());
    }

    @Test
    void hopperHasFiveSlotsAndCannotBeConfusedWithChest() {
        Fixture hopper = new Fixture();
        hopper.holder = Holder.HOPPER;
        hopper.slot = 4;
        hopper.containerPath = "slot:4";
        assertEquals(Outcome.ELIGIBLE, SQLitePhysicalProofReconciliationPlanner.plan(
                projection(OLD, LocationDescriptor.Type.BLOCK_CONTAINER, "slot:4"),
                hopper.build()).outcome());
        hopper.slot = 5;
        assertResult(projection(OLD, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO),
                hopper, Outcome.CONFLICT, Reason.INVALID_HOLDER_SLOT);
    }

    @Test
    void entityHeldAndMalformedModernRootsAreNeverSilentlyMapped() {
        assertRefused("minecraft:world:entity:11111111-2222-3333-4444-555555555555",
                unused -> {}, Outcome.ABSENT, Reason.NO_CURRENT_LEGACY_REFERENCE);
        assertRefused("block-v2:bad:15:70:8",
                unused -> {}, Outcome.ABSENT, Reason.NO_CURRENT_LEGACY_REFERENCE);
        assertRefused("double-chest:bad:15:70:8|16:70:8:left=0",
                unused -> {}, Outcome.ABSENT, Reason.NO_CURRENT_LEGACY_REFERENCE);
    }

    @Test
    void missingProofOrForgedSlotPathCannotPromoteLastKnownEvidence() {
        CurrentEvidence evidence = projection(OLD, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO);
        Decision absent = SQLitePhysicalProofReconciliationPlanner.plan(evidence, null);
        assertEquals(Outcome.ABSENT, absent.outcome());
        assertEquals(Reason.NO_PHYSICAL_PROOF, absent.reason());
        Fixture wrongSlot = new Fixture();
        wrongSlot.slot = 1;
        wrongSlot.containerPath = "slot:1";
        assertResult(evidence, wrongSlot, Outcome.CONFLICT, Reason.UNVERIFIED_SLOT_PATH);
        Fixture malformed = new Fixture();
        malformed.containerPath = "slot:0/shulker:3";
        assertResult(evidence, malformed, Outcome.CONFLICT, Reason.UNVERIFIED_SLOT_PATH);
    }

    @Test
    void secondHalfSlotCannotAliasLeftHalfOrInventNestedOffset() {
        Fixture f = pairFixture();
        f.slot = 27;
        f.containerPath = "slot:27/shulker:0";
        CurrentEvidence nested = projection(
                "root:BLOCK_CONTAINER:minecraft:world:16:70:8",
                LocationDescriptor.Type.NESTED_CONTAINER, "slot:0/shulker:0");
        Decision valid = SQLitePhysicalProofReconciliationPlanner.plan(nested, f.build());
        assertEquals(Outcome.ELIGIBLE, valid.outcome());
        assertEquals("slot:27/shulker:0", valid.proposedContainerPath());
        f.containerPath = "slot:0/shulker:0";
        assertResult(nested, f, Outcome.CONFLICT, Reason.UNVERIFIED_SLOT_PATH);
    }

    @Test
    void worldRecreationAndRenameWithoutRecordedLineageRemainUnresolved() {
        Fixture recreated = new Fixture();
        recreated.worldId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        recreated.lineage = false;
        assertRefused(OLD, f -> { f.worldId = recreated.worldId; f.lineage = false; },
                Outcome.AMBIGUOUS, Reason.WORLD_LINEAGE_UNPROVEN);
        assertRefused("minecraft:old_world:15:70:8", f -> f.lineage = false,
                Outcome.AMBIGUOUS, Reason.WORLD_LINEAGE_UNPROVEN);
    }

    private static String paired(Position first, Position second, int left) {
        return "double-chest:" + WORLD + ":" + first.xyz() + "|"
                + second.xyz() + ":left=" + left;
    }

    private static Fixture pairFixture() {
        Fixture f = new Fixture();
        f.holder = Holder.DOUBLE_CHEST;
        f.second = new Position(16, 70, 8);
        f.left = f.first;
        return f;
    }

    private static void copyPair(Fixture target, Fixture source) {
        target.holder = source.holder;
        target.second = source.second;
        target.left = source.left;
        target.secondLoaded = source.secondLoaded;
    }

    private static void assertRefused(String key, Consumer<Fixture> mutate,
            Outcome outcome, Reason reason) {
        Fixture f = new Fixture();
        mutate.accept(f);
        assertResult(projection(key, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO),
                f, outcome, reason);
    }

    private static void assertResult(CurrentEvidence current, Fixture f,
            Outcome outcome, Reason reason) {
        Decision decision = SQLitePhysicalProofReconciliationPlanner.plan(current, f.build());
        assertEquals(outcome, decision.outcome());
        assertEquals(reason, decision.reason());
        assertNull(decision.proposedLocationKey());
        assertNull(decision.proposedContainerPath());
    }

    private static Decision plan(String key, Consumer<Fixture> mutate) {
        Fixture f = new Fixture();
        mutate.accept(f);
        return SQLitePhysicalProofReconciliationPlanner.plan(
                projection(key, LocationDescriptor.Type.BLOCK_CONTAINER, SLOT_ZERO), f.build());
    }

    private static CurrentEvidence projection(String key, LocationDescriptor.Type type,
            String path) {
        return projection(key, type, path, InstanceCurrentState.State.CONFIRMED_NOW, false);
    }

    private static CurrentEvidence projection(String key, LocationDescriptor.Type type,
            String path, InstanceCurrentState.State state, boolean conflict) {
        return new CurrentEvidence(INSTANCE, new LocationDescriptor(type, key, path),
                state, 4, 17, 100, 8, conflict);
    }

    private static class Fixture {
        UUID instanceId = INSTANCE, worldId = WORLD;
        Holder holder = Holder.SINGLE_CHEST;
        Position first = new Position(15, 70, 8), second, left;
        String containerPath = SLOT_ZERO;
        int slot, matches = 1, unresolvedWorlds;
        long revision = 4, receipt = 17, generation = 8, captured = 200;
        boolean firstLoaded = true, secondLoaded = true, geometryVerified = true;
        boolean coverage = true, lineage = true, settled = true, dirty, ack = true;

        PhysicalProof build() {
            return new PhysicalProof(instanceId, worldId, holder,
                    new Geometry(first, second, left, firstLoaded,
                            secondLoaded, geometryVerified, coverage),
                    containerPath, slot, matches, revision, receipt, generation, captured,
                    unresolvedWorlds, lineage, settled, dirty, ack);
        }
    }
}
