package net.enthusia.loreitems.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DirectDeliveryStateTest {
    @Test
    void followsTheDurableForwardPath() {
        DirectDeliveryState state = DirectDeliveryState.PENDING;
        state = state.transitionTo(DirectDeliveryState.RESERVED);
        state = state.transitionTo(DirectDeliveryState.APPLIED);
        state = state.transitionTo(DirectDeliveryState.VERIFIED);
        state = state.transitionTo(DirectDeliveryState.COMPLETED);

        assertEquals(DirectDeliveryState.COMPLETED, state);
    }

    @Test
    void permitsReviewFromAnyNonTerminalState() {
        assertEquals(
                DirectDeliveryState.REVIEW_REQUIRED,
                DirectDeliveryState.APPLIED.transitionTo(DirectDeliveryState.REVIEW_REQUIRED));
    }

    @Test
    void rejectsSkippingVerification() {
        assertThrows(
                IllegalStateException.class,
                () -> DirectDeliveryState.APPLIED.transitionTo(DirectDeliveryState.COMPLETED));
    }
}
