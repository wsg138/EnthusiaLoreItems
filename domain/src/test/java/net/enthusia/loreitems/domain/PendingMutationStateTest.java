package net.enthusia.loreitems.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PendingMutationStateTest {
    @Test
    void followsDurableMutationProtocol() {
        PendingMutationState state = PendingMutationState.PENDING;
        state = state.transitionTo(PendingMutationState.CLAIMED);
        state = state.transitionTo(PendingMutationState.APPLIED);
        state = state.transitionTo(PendingMutationState.VERIFIED);
        state = state.transitionTo(PendingMutationState.COMPLETED);

        assertEquals(PendingMutationState.COMPLETED, state);
        assertTrue(state.terminal());
        PendingMutationState completed = state;
        assertThrows(
                IllegalStateException.class,
                () -> completed.transitionTo(PendingMutationState.REVIEW_REQUIRED));
    }

    @Test
    void ambiguityCanEnterReviewAndBeResolvedExplicitly() {
        PendingMutationState reviewed =
                PendingMutationState.APPLIED.transitionTo(PendingMutationState.REVIEW_REQUIRED);

        assertEquals(
                PendingMutationState.PENDING,
                reviewed.transitionTo(PendingMutationState.PENDING));
        assertEquals(
                PendingMutationState.CANCELLED,
                reviewed.transitionTo(PendingMutationState.CANCELLED));
        assertFalse(reviewed.terminal());
        assertTrue(PendingMutationState.CANCELLED.terminal());
    }
}
