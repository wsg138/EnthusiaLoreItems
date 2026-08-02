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
    void permitsSafeCancellationBeforeReservation() {
        assertEquals(
                DirectDeliveryState.CANCELLED,
                DirectDeliveryState.PENDING.transitionTo(DirectDeliveryState.CANCELLED));
    }

    @Test
    void rejectsCancellationAfterReservation() {
        assertThrows(
                IllegalStateException.class,
                () -> DirectDeliveryState.RESERVED.transitionTo(DirectDeliveryState.CANCELLED));
    }

    @Test
    void rejectsSkippingVerification() {
        assertThrows(
                IllegalStateException.class,
                () -> DirectDeliveryState.APPLIED.transitionTo(DirectDeliveryState.COMPLETED));
    }
}
