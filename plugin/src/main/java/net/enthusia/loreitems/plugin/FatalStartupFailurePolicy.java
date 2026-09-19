package net.enthusia.loreitems.plugin;

import java.util.Objects;
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
}
