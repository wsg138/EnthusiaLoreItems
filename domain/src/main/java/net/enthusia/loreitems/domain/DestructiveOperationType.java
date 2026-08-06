package net.enthusia.loreitems.domain;

public enum DestructiveOperationType {
    EXACT_INSTANCE_REMOVAL(true, false),
    PURGE_DEFINITION(false, false),
    DELETE_DEFINITION(false, true);

    private final boolean requiresExactInstance;
    private final boolean removesDefinition;

    DestructiveOperationType(boolean requiresExactInstance, boolean removesDefinition) {
        this.requiresExactInstance = requiresExactInstance;
        this.removesDefinition = removesDefinition;
    }

    public boolean exactInstanceRequired() {
        return requiresExactInstance;
    }

    public boolean deletesDefinition() {
        return removesDefinition;
    }
}