package net.enthusia.loreitems.domain;

public enum DestructiveOperationType {
    EXACT_INSTANCE_REMOVAL(true, false),
    PURGE_DEFINITION(false, false),
    DELETE_DEFINITION(false, true);

    private final boolean exactInstanceRequired;
    private final boolean deletesDefinition;

    DestructiveOperationType(boolean exactInstanceRequired, boolean deletesDefinition) {
        this.exactInstanceRequired = exactInstanceRequired;
        this.deletesDefinition = deletesDefinition;
    }

    public boolean exactInstanceRequired() {
        return exactInstanceRequired;
    }

    public boolean deletesDefinition() {
        return deletesDefinition;
    }
}