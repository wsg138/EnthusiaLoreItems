package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageRequestTest {
    @Test
    void advancesWithinExplicitBounds() {
        assertEquals(new PageRequest(25, 25), PageRequest.first(25).next());
    }

    @Test
    void rejectsUnboundedRequests() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PageRequest(0, PageRequest.MAX_LIMIT + 1));
    }
}
