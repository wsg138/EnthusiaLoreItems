package net.enthusia.loreitems.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public record LocationDescriptor(Type type, String locationKey, String containerPath)
        implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int MAX_LOCATION_KEY_LENGTH = 512;
    public static final int MAX_CONTAINER_PATH_LENGTH = 2_048;

    public LocationDescriptor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(locationKey, "locationKey");
        locationKey = locationKey.strip();
        if (locationKey.isEmpty() || locationKey.length() > MAX_LOCATION_KEY_LENGTH) {
            throw new IllegalArgumentException("Invalid location key");
        }
        if (containerPath != null) {
            containerPath = containerPath.strip();
            if (containerPath.isEmpty() || containerPath.length() > MAX_CONTAINER_PATH_LENGTH) {
                throw new IllegalArgumentException("Invalid container path");
            }
        }
    }

    public enum Type {
        PLAYER_INVENTORY,
        PLAYER_ENDER_CHEST,
        BLOCK_CONTAINER,
        NESTED_CONTAINER,
        DROPPED_ITEM,
        ITEM_FRAME,
        ARMOR_STAND,
        QUEUED_DELIVERY,
        PENDING_MUTATION,
        VOID_DESTROYED,
        DUPLICATE_CONFLICT
    }
}
