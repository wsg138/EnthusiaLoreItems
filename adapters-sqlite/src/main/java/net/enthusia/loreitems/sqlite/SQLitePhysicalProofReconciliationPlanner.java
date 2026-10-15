package net.enthusia.loreitems.sqlite;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.LocationDescriptor;

/**
 * Pure, offline issue #103 preflight: no SQLite connection, writes, Bukkit access or
 * historical world-name -> world-UUID alias. ELIGIBLE permits review of a future
 * receipt-backed reobservation transaction, never an automatic migration.
 */
public final class SQLitePhysicalProofReconciliationPlanner {
    private static final String NESTED_ROOT = "root:BLOCK_CONTAINER:";
    private static final Pattern LEGACY_BLOCK = Pattern.compile(
            "([a-z0-9_.-]+:[a-z0-9_./-]+):(-?\\d+):(-?\\d+):(-?\\d+)");

    private SQLitePhysicalProofReconciliationPlanner() {}

    public enum Outcome { ABSENT, AMBIGUOUS, STALE, CONFLICT, ELIGIBLE }

    public enum Reason {
        NO_CURRENT_LEGACY_REFERENCE, NO_PHYSICAL_PROOF, NO_MATCHING_ITEM,
        MULTIPLE_MATCHING_ITEMS, WORLD_LINEAGE_UNPROVEN, WORLD_NAME_COLLISION,
        UNLOADED_OR_PARTIAL_GEOMETRY, UNVERIFIED_CHEST_GEOMETRY,
        INVALID_HOLDER_SLOT, UNVERIFIED_SLOT_PATH, PDC_IDENTITY_MISMATCH, UNEXPECTED_CURRENT_STATE,
        ACTIVE_CONFLICT, STALE_REVISION_OR_RECEIPT, STALE_GENERATION,
        DIRTY_OR_UNSETTLED, ACK_NOT_DURABLE, STALE_PHYSICAL_CAPTURE,
        LOCATION_OR_NESTED_PATH_MISMATCH, MALFORMED_LEGACY_LOCATION,
        PHYSICAL_REOBSERVATION_ONLY
    }

    public enum Holder { SINGLE_CHEST, HOPPER, DOUBLE_CHEST }

    /** Coordinates are block positions, never a composite DoubleChest.getLocation(). */
    public record Position(int x, int y, int z) implements Comparable<Position> {
        public boolean adjacent(Position other) {
            long dx = Math.abs((long) x - other.x);
            long dz = Math.abs((long) z - other.z);
            return y == other.y && dx + dz == 1L;
        }

        @Override
        public int compareTo(Position other) {
            int byX = Integer.compare(x, other.x);
            int byY = Integer.compare(y, other.y);
            return byX != 0 ? byX : byY != 0 ? byY : Integer.compare(z, other.z);
        }

        String xyz() {
            return x + ":" + y + ":" + z;
        }
    }

    /**
     * first and second must be the actual naturally loaded chest blocks, not
     * guessed halves. apiLeft is obtained from the verified 54-slot Paper view.
     */
    public record Geometry(Position first, Position second, Position apiLeft,
                           boolean firstLoaded, boolean secondLoaded,
                           boolean paperGeometryVerified, boolean completeCoverage) {
        public Geometry {
            Objects.requireNonNull(first, "first");
        }

        boolean complete(Holder holder) {
            if (!firstLoaded || !completeCoverage || !paperGeometryVerified) {
                return false;
            }
            return holder != Holder.DOUBLE_CHEST || secondLoaded;
        }

        boolean canonical(Holder holder) {
            if (holder != Holder.DOUBLE_CHEST) {
                return second == null && apiLeft == null;
            }
            return second != null && apiLeft != null
                    && first.compareTo(second) < 0 && first.adjacent(second)
                    && (apiLeft.equals(first) || apiLeft.equals(second));
        }

        boolean includes(Position p) {
            return first.equals(p) || second != null && second.equals(p);
        }

        String canonicalKey(Holder holder, UUID worldId) {
            if (holder != Holder.DOUBLE_CHEST) {
                return "block-v2:" + worldId + ":" + first.xyz();
            }
            String left = apiLeft.equals(first) ? "0" : "1";
            return "double-chest:" + worldId + ":" + first.xyz()
                    + "|" + second.xyz() + ":left=" + left;
        }
    }

    /**
     * Future persisted, same-instance projection and mutation watermark, captured
     * consistently for compare-and-swap. Current SQLite does NOT persist a
     * generation watermark; no production adapter for this type exists.
     */
    public record CurrentEvidence(UUID instanceId, LocationDescriptor location,
                                  InstanceCurrentState.State state, long revision,
                                  long lastObservationId, long updatedAtMillis,
                                  long settledGeneration, boolean activeConflict) {
        public CurrentEvidence {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(state, "state");
            if (revision < 0 || lastObservationId < 1 || settledGeneration < 0
                    || updatedAtMillis < 0) {
                throw new IllegalArgumentException("Invalid persisted evidence");
            }
        }
    }

    /**
     * Future trusted Paper proof; PDC ID and physical UUID are independent of
     * the legacy world name. lineageVerified means independent provenance has
     * tied THIS source receipt to THIS instance, not a current name lookup.
     * unresolvedNameCandidates counts known unresolved same-name world claims.
     */
    public record PhysicalProof(
            UUID pdcInstanceId, UUID actualWorldId, Holder holder, Geometry geometry,
            String containerPath, int physicalSlot, int matchingInstanceCount,
            long expectedRevision, long sourceObservationId, long eventGeneration,
            long capturedAtMillis, int unresolvedNameCandidates,
            boolean lineageVerified, boolean mutationSettled, boolean dirty,
            boolean durableAcknowledgement) {
        public PhysicalProof {
            Objects.requireNonNull(pdcInstanceId, "pdcInstanceId");
            Objects.requireNonNull(actualWorldId, "actualWorldId");
            Objects.requireNonNull(holder, "holder");
            Objects.requireNonNull(geometry, "geometry");
            if (matchingInstanceCount < 0 || unresolvedNameCandidates < 0
                    || expectedRevision < 0 || sourceObservationId < 1
                    || eventGeneration < 0 || capturedAtMillis < 0) {
                throw new IllegalArgumentException("Invalid proof counters");
            }
        }

        boolean validSlot() {
            int slots = switch (holder) {
                case HOPPER -> 5;
                case SINGLE_CHEST -> 27;
                case DOUBLE_CHEST -> 54;
            };
            return physicalSlot >= 0 && physicalSlot < slots;
        }

        boolean matchesVerifiedSlotPath(String expectedPath) {
            return Objects.equals(containerPath, expectedPath);
        }
    }

    /** Proposed key is populated only for ELIGIBLE; nothing is persisted. */
    public record Decision(Outcome outcome, Reason reason,
                           String proposedLocationKey, String proposedContainerPath) {
        static Decision refuse(Outcome outcome, Reason reason) {
            return new Decision(outcome, reason, null, null);
        }
    }

    public static Decision plan(CurrentEvidence current, PhysicalProof proof) {
        if (current == null || current.location() == null
                || !isLegacyReference(current.location())) {
            return Decision.refuse(Outcome.ABSENT, Reason.NO_CURRENT_LEGACY_REFERENCE);
        }
        if (current.activeConflict() || current.state() == InstanceCurrentState.State.CONFLICTING) {
            return Decision.refuse(Outcome.CONFLICT, Reason.ACTIVE_CONFLICT);
        }
        if (current.state() != InstanceCurrentState.State.CONFIRMED_NOW
                && current.state() != InstanceCurrentState.State.LAST_CONFIRMED) {
            return Decision.refuse(Outcome.CONFLICT, Reason.UNEXPECTED_CURRENT_STATE);
        }
        if (proof == null) {
            return Decision.refuse(Outcome.ABSENT, Reason.NO_PHYSICAL_PROOF);
        }
        return inspectPhysical(current, proof);
    }

    private static Decision inspectPhysical(CurrentEvidence current, PhysicalProof proof) {
        if (!current.instanceId().equals(proof.pdcInstanceId())) {
            return Decision.refuse(Outcome.CONFLICT, Reason.PDC_IDENTITY_MISMATCH);
        }
        if (proof.matchingInstanceCount() == 0) {
            return Decision.refuse(Outcome.ABSENT, Reason.NO_MATCHING_ITEM);
        }
        if (proof.matchingInstanceCount() != 1) {
            return Decision.refuse(Outcome.AMBIGUOUS, Reason.MULTIPLE_MATCHING_ITEMS);
        }
        if (proof.expectedRevision() != current.revision()
                || proof.sourceObservationId() != current.lastObservationId()) {
            return Decision.refuse(Outcome.STALE, Reason.STALE_REVISION_OR_RECEIPT);
        }
        if (proof.eventGeneration() != current.settledGeneration()) {
            return Decision.refuse(Outcome.STALE, Reason.STALE_GENERATION);
        }
        if (proof.dirty() || !proof.mutationSettled()) {
            return Decision.refuse(Outcome.STALE, Reason.DIRTY_OR_UNSETTLED);
        }
        if (!proof.durableAcknowledgement()) {
            return Decision.refuse(Outcome.STALE, Reason.ACK_NOT_DURABLE);
        }
        if (proof.capturedAtMillis() <= current.updatedAtMillis()) {
            return Decision.refuse(Outcome.STALE, Reason.STALE_PHYSICAL_CAPTURE);
        }
        return inspectIdentity(current, proof);
    }

    private static Decision inspectIdentity(CurrentEvidence current, PhysicalProof proof) {
        if (proof.unresolvedNameCandidates() > 0) {
            return Decision.refuse(Outcome.AMBIGUOUS, Reason.WORLD_NAME_COLLISION);
        }
        if (!proof.lineageVerified()) {
            return Decision.refuse(Outcome.AMBIGUOUS, Reason.WORLD_LINEAGE_UNPROVEN);
        }
        Geometry geometry = proof.geometry();
        if (!geometry.canonical(proof.holder())) {
            return Decision.refuse(Outcome.CONFLICT, Reason.UNVERIFIED_CHEST_GEOMETRY);
        }
        if (!geometry.complete(proof.holder())) {
            return Decision.refuse(Outcome.AMBIGUOUS, Reason.UNLOADED_OR_PARTIAL_GEOMETRY);
        }
        if (!proof.validSlot()) {
            return Decision.refuse(Outcome.CONFLICT, Reason.INVALID_HOLDER_SLOT);
        }
        return inspectLocation(current, proof);
    }

    private static Decision inspectLocation(CurrentEvidence current, PhysicalProof proof) {
        LocationDescriptor location = current.location();
        Matcher match = LEGACY_BLOCK.matcher(stripNested(location));
        if (!match.matches()) {
            return Decision.refuse(Outcome.CONFLICT, Reason.MALFORMED_LEGACY_LOCATION);
        }
        Position legacy;
        try {
            legacy = new Position(Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)));
        } catch (NumberFormatException exception) {
            return Decision.refuse(Outcome.CONFLICT, Reason.MALFORMED_LEGACY_LOCATION);
        }
        if (!proof.geometry().includes(legacy)) {
            return Decision.refuse(Outcome.CONFLICT, Reason.LOCATION_OR_NESTED_PATH_MISMATCH);
        }
        String expectedPath = translatedPath(location.containerPath(), legacy, proof);
        if (expectedPath == null || !proof.matchesVerifiedSlotPath(expectedPath)) {
            return Decision.refuse(Outcome.CONFLICT, Reason.UNVERIFIED_SLOT_PATH);
        }
        String prefix = location.type() == LocationDescriptor.Type.NESTED_CONTAINER
                ? NESTED_ROOT : "";
        return new Decision(Outcome.ELIGIBLE, Reason.PHYSICAL_REOBSERVATION_ONLY,
                prefix + proof.geometry().canonicalKey(proof.holder(), proof.actualWorldId()),
                expectedPath);
    }

    private static String translatedPath(String oldPath, Position oldHalf,
            PhysicalProof proof) {
        if (oldPath == null || !oldPath.startsWith("slot:")) {
            return null;
        }
        int slash = oldPath.indexOf('/');
        String digits = oldPath.substring(5, slash < 0 ? oldPath.length() : slash);
        int localSlot;
        try {
            localSlot = Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            return null;
        }
        int expectedSlot = localSlot;
        if (proof.holder() == Holder.DOUBLE_CHEST) {
            if (localSlot < 0 || localSlot >= 27) {
                return null;
            }
            expectedSlot += oldHalf.equals(proof.geometry().apiLeft()) ? 0 : 27;
        }
        if (expectedSlot != proof.physicalSlot()) {
            return null;
        }
        return "slot:" + expectedSlot + (slash < 0 ? "" : oldPath.substring(slash));
    }

    private static boolean isLegacyReference(LocationDescriptor location) {
        if (location.type() != LocationDescriptor.Type.BLOCK_CONTAINER
                && location.type() != LocationDescriptor.Type.NESTED_CONTAINER) {
            return false;
        }
        if (location.type() == LocationDescriptor.Type.NESTED_CONTAINER
                && !location.locationKey().startsWith(NESTED_ROOT)) {
            return false;
        }
        String key = stripNested(location);
        return !key.startsWith("block-v2:") && !key.startsWith("double-chest:")
                && SQLiteTrackingLegacyKeyFence.legacyWorldName(key) != null;
    }

    private static String stripNested(LocationDescriptor location) {
        String key = location.locationKey();
        return location.type() == LocationDescriptor.Type.NESTED_CONTAINER
                && key.startsWith(NESTED_ROOT) ? key.substring(NESTED_ROOT.length()) : key;
    }
}
