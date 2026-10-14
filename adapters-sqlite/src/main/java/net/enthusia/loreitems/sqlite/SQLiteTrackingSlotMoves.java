package net.enthusia.loreitems.sqlite;

import net.enthusia.loreitems.domain.LocationDescriptor;

/** Holder identity is separate from an item's exact slot within that holder. */
final class SQLiteTrackingSlotMoves {
    private SQLiteTrackingSlotMoves() {}

    static boolean sameHolder(LocationDescriptor first, LocationDescriptor second) {
        if (first == null || second == null || first.type() != second.type()
                || !first.locationKey().equals(second.locationKey())) {
            return false;
        }
        return switch (first.type()) {
            case PLAYER_INVENTORY, PLAYER_ENDER_CHEST, BLOCK_CONTAINER -> true;
            default -> false;
        };
    }
}
