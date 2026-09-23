package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import org.junit.jupiter.api.Test;

class GiveLoreItemCommandExecutorMessageTest {
    @Test
    void acceptedMessageCoversImmediateAndDeferredDeliveryTruthfully() {
        assertEquals(
                "Lore item delivery accepted. It will deliver immediately when possible, otherwise remain queued until the player is online with inventory space.",
                GiveLoreItemCommandExecutor.commandMessage(new LoreDeliveryResult(
                        LoreDeliveryStatus.ACCEPTED_QUEUED,
                        "message-test",
                        "accepted")));
    }
}
