package net.enthusia.loreitems.application;

import java.util.List;
import java.util.Objects;

public record Page<T>(List<T> items, int offset, int limit, boolean hasMore) {
    public Page {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        if (offset < 0 || limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid page bounds");
        }
        if (items.size() > limit) {
            throw new IllegalArgumentException("Page contains more items than its limit");
        }
    }
}
