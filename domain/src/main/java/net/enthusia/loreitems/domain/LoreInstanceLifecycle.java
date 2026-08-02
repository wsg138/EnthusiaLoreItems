package net.enthusia.loreitems.domain;

import java.util.Objects;

public enum LoreInstanceLifecycle {
    ACTIVE,
    VOID_DESTROYED,
    REMOVED;

    public boolean terminal() {
        return this != ACTIVE;
    }

    public void transitionTo(LoreInstanceLifecycle target) {
        Objects.requireNonNull(target, "target");
        if (this != ACTIVE || target == ACTIVE) {
            throw new IllegalStateException(
                    "Invalid lore instance lifecycle transition: " + this + " -> " + target);
        }
    }
}
