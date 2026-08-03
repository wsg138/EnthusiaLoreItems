package net.enthusia.loreitems.application;

import java.util.Objects;

public final class ItemCodecException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ItemCodecFailure failure;

    public ItemCodecException(ItemCodecFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ItemCodecException(ItemCodecFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ItemCodecFailure failure() {
        return failure;
    }
}
