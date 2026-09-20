package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FatalStartupFailurePolicyTest {
    @Test
    void disableSchedulingFailureCannotLeaveWritesAvailable() {
        AtomicBoolean writesAvailable = new AtomicBoolean(true);
        AtomicBoolean disableAttempted = new AtomicBoolean();
        AtomicReference<RuntimeException> observedFailure = new AtomicReference<>();
        RuntimeException expectedFailure = new IllegalStateException("scheduler rejected disable");

        FatalStartupFailurePolicy.revokeWritesThenRequestDisable(
                () -> writesAvailable.set(false),
                () -> {
                    assertFalse(writesAvailable.get());
                    disableAttempted.set(true);
                    throw expectedFailure;
                },
                observedFailure::set);

        assertFalse(writesAvailable.get());
        assertTrue(disableAttempted.get());
        assertSame(expectedFailure, observedFailure.get());
    }

    @Test
    void cleanupFailureStillAttemptsDisableAfterWritesAreRevoked() {
        AtomicBoolean writesAvailable = new AtomicBoolean(true);
        AtomicBoolean disableAttempted = new AtomicBoolean();
        AtomicReference<RuntimeException> observedFailure = new AtomicReference<>();
        RuntimeException cleanupFailure = new IllegalStateException("cleanup failed");

        FatalStartupFailurePolicy.revokeWritesThenCleanupAndRequestDisable(
                () -> writesAvailable.set(false),
                () -> {
                    assertFalse(writesAvailable.get());
                    throw cleanupFailure;
                },
                () -> {
                    assertFalse(writesAvailable.get());
                    disableAttempted.set(true);
                },
                observedFailure::set);

        assertFalse(writesAvailable.get());
        assertTrue(disableAttempted.get());
        assertSame(cleanupFailure, observedFailure.get());
    }

}
