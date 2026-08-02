package net.enthusia.loreitems.domain;

public enum LoreInstanceLifecycle {
    ACTIVE,
    VOID_DESTROYED,
    REMOVED;

    public boolean terminal() {
        return this != ACTIVE;
    }

    public void transitionTo(LoreInstanceLifecycle target) {
        if (this != ACTIVE || target == ACTIVE) {
            throw new IllegalStateException(
                    "Invalid lore instance lifecycle transition: " + this + " -> " + target);
        }
    }
}
