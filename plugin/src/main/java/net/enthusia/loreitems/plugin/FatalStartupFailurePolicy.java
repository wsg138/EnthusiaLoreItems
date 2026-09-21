package net.enthusia.loreitems.plugin;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keeps write availability fail-closed before a fatal startup failure requests plugin disable. */
final class FatalStartupFailurePolicy {
    private FatalStartupFailurePolicy() {}

    static void revokeWritesThenRequestDisable(
            Runnable revokeWrites,
            Runnable requestDisable,
            Consumer<RuntimeException> disableFailureHandler) {
        Objects.requireNonNull(revokeWrites, "revokeWrites").run();
        try {
            Objects.requireNonNull(requestDisable, "requestDisable").run();
        } catch (RuntimeException exception) {
            Objects.requireNonNull(disableFailureHandler, "disableFailureHandler").accept(exception);
        }
    }

    static boolean allowDeferredActivation(
            BooleanSupplier activationAllowed,
            Runnable cleanup) {
        Objects.requireNonNull(activationAllowed, "activationAllowed");
        Objects.requireNonNull(cleanup, "cleanup");
        if (activationAllowed.getAsBoolean()) {
            return true;
        }
        cleanup.run();
        return false;
    }

    static void revokeWritesThenCleanupAndRequestDisable(
            Runnable revokeWrites,
            Runnable cleanup,
            Runnable requestDisable,
            Consumer<RuntimeException> failureHandler) {
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(requestDisable, "requestDisable");
        Objects.requireNonNull(failureHandler, "failureHandler");
        revokeWritesThenRequestDisable(
                revokeWrites,
                () -> {
                    try {
                        cleanup.run();
                    } finally {
                        requestDisable.run();
                    }
                },
                failureHandler);
    }
}
