package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import org.junit.jupiter.api.Test;

class GiveLoreItemCommandExecutorMessageTest {
    @Test
    void acceptedMessageCoversImmediateAndDeferredDeliveryTruthfully() {
        assertEquals(
                "Lore item queued. It will deliver when the player is online with inventory space. If that is already true, delivery may happen immediately.",
                GiveLoreItemCommandExecutor.commandMessage(new LoreDeliveryResult(
                        LoreDeliveryStatus.ACCEPTED_QUEUED,
                        "message-test",
                        "accepted")));
    }
}
