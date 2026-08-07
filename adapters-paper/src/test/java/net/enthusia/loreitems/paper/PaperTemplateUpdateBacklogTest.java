package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperTemplateUpdateBacklogTest {
    @Test
    void scanBacklogHasTwoBoundedTiersAndRejectsBeyondTotalCapacity() {
        PaperTemplateUpdateScanBacklog backlog = new PaperTemplateUpdateScanBacklog(2);
        List<PaperInventoryReference> references = references(5);

        assertEquals(PaperTemplateUpdateScanOfferResult.READY, backlog.offer(references.get(0)));
        assertEquals(PaperTemplateUpdateScanOfferResult.READY, backlog.offer(references.get(1)));
        assertEquals(PaperTemplateUpdateScanOfferResult.DEFERRED, backlog.offer(references.get(2)));
        assertEquals(PaperTemplateUpdateScanOfferResult.DEFERRED, backlog.offer(references.get(3)));
        assertEquals(PaperTemplateUpdateScanOfferResult.REJECTED, backlog.offer(references.get(4)));
        assertEquals(PaperTemplateUpdateScanOfferResult.ALREADY_QUEUED, backlog.offer(references.get(1)));

        assertEquals(references.get(0), backlog.poll());
        assertEquals(PaperTemplateUpdateScanOfferResult.DEFERRED, backlog.offer(references.get(4)));
        assertEquals(references.get(1), backlog.poll());
        assertEquals(references.get(2), backlog.poll());
        assertEquals(references.get(3), backlog.poll());
        assertEquals(references.get(4), backlog.poll());
        assertNull(backlog.poll());
        assertTrue(backlog.isEmpty());
    }

    @Test
    void scanCapacityDerivedFromBudgetIsClamped() {
        assertEquals(512, PaperTemplateUpdateScanBacklog.capacityForBudget(1));
        assertEquals(512, PaperTemplateUpdateScanBacklog.capacityForBudget(16));
        assertEquals(1_024, PaperTemplateUpdateScanBacklog.capacityForBudget(32));
        assertEquals(4_096, PaperTemplateUpdateScanBacklog.capacityForBudget(128));
        assertEquals(4_096, PaperTemplateUpdateScanBacklog.capacityForBudget(10_000));
    }

    @Test
    void retryBacklogDeduplicatesRejectsAtCapacityAndReusesFreedSlot() {
        PaperTemplateUpdateRetryBacklog backlog = new PaperTemplateUpdateRetryBacklog(2);
        List<PaperInventoryReference> references = references(3);

        assertTrue(backlog.offer(references.get(0)));
        assertFalse(backlog.offer(references.get(0)));
        assertTrue(backlog.offer(references.get(1)));
        assertFalse(backlog.offer(references.get(2)));

        assertEquals(references.get(0), backlog.poll());
        assertTrue(backlog.offer(references.get(2)));
        assertEquals(references.get(1), backlog.poll());
        assertEquals(references.get(2), backlog.poll());
        assertNull(backlog.poll());
    }

    private static List<PaperInventoryReference> references(int count) {
        List<PaperInventoryReference> references = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            references.add(new PaperInventoryReference.PlayerMain(new UUID(0L, index + 1L)));
        }
        return List.copyOf(references);
    }
}
