package net.enthusia.loreitems.application;

public record PageRequest(int offset, int limit) {
    public static final int MAX_LIMIT = 200;

    public PageRequest {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static PageRequest first(int limit) {
        return new PageRequest(0, limit);
    }

    public PageRequest next() {
        return new PageRequest(Math.addExact(offset, limit), limit);
    }
}
