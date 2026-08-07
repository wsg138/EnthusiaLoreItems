package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CampaignRecipientCountsTest {
    @Test
    void totalAndRemainingFollowTheSevenStateContract() {
        CampaignRecipientCounts counts = new CampaignRecipientCounts(1, 2, 3, 4, 5, 6, 7);

        assertEquals(28, counts.total());
        assertEquals(15, counts.remaining());
    }

    @Test
    void negativeStateCountsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CampaignRecipientCounts(-1, 0, 0, 0, 0, 0, 0));
    }
}
