package net.enthusia.loreitems.plugin;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Runs startup component activations in order and stops at the first failed activation. */
final class StartupActivationSequence {
    private StartupActivationSequence() {}

    static boolean run(BooleanSupplier... activations) {
        Objects.requireNonNull(activations, "activations");
        for (BooleanSupplier activation : activations) {
            if (!Objects.requireNonNull(activation, "activation").getAsBoolean()) {
                return false;
            }
        }
        return true;
    }
}
