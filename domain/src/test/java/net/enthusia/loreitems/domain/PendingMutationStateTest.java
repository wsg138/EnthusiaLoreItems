package net.enthusia.loreitems.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        PendingMutationState completed = state;
        assertThrows(
                IllegalStateException.class,
                () -> completed.transitionTo(PendingMutationState.REVIEW_REQUIRED));
    }

    @Test
    void ambiguityCanEnterReviewBeforeCompletion() {
        assertEquals(
                PendingMutationState.REVIEW_REQUIRED,
                PendingMutationState.APPLIED.transitionTo(PendingMutationState.REVIEW_REQUIRED));
    }
}
