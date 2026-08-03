package net.enthusia.loreitems.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DistributionCampaignTest {
    @Test
    void campaignStateMachineRejectsTerminalAndSkippedTransitions() {
        assertEquals(
                DistributionCampaignState.ACTIVE,
                DistributionCampaignState.DRAFT.transitionTo(
                        DistributionCampaignState.ACTIVE));
        assertEquals(
                DistributionCampaignState.PAUSED,
                DistributionCampaignState.ACTIVE.transitionTo(
                        DistributionCampaignState.PAUSED));
        assertTrue(DistributionCampaignState.PAUSED.canTransitionTo(
                DistributionCampaignState.COMPLETED));
        assertFalse(DistributionCampaignState.COMPLETED.canTransitionTo(
                DistributionCampaignState.ACTIVE));
        assertThrows(
                IllegalStateException.class,
                () -> DistributionCampaignState.DRAFT.transitionTo(
                        DistributionCampaignState.COMPLETED));
    }

    @Test
    void recipientStateMachineSupportsSafeRetryAndTerminalFencing() {
        assertTrue(CampaignRecipientState.PENDING_OFFLINE.canTransitionTo(
                CampaignRecipientState.RESERVED));
        assertTrue(CampaignRecipientState.RESERVED.canTransitionTo(
                CampaignRecipientState.PENDING_SPACE));
        assertTrue(CampaignRecipientState.RESERVED.canTransitionTo(
                CampaignRecipientState.DELIVERED));
        assertFalse(CampaignRecipientState.DELIVERED.canTransitionTo(
                CampaignRecipientState.RESERVED));
    }

    @Test
    void unresolvedNameKeyIsCaseInsensitiveAndPreservesFloodgatePrefix() {
        CampaignRecipient recipient = CampaignRecipient.unresolvedName(
                UUID.randomUUID(), 0, "  *BedRockPlayer  ", 1_000L);

        assertEquals("name:*bedrockplayer", recipient.recipientKey().value());
        assertEquals("*BedRockPlayer", recipient.originalValue());
        assertEquals(CampaignRecipientState.PENDING_NAME, recipient.state());
    }

    @Test
    void boundNameKeyCanRetainSnapshotIdentityWhileUuidBecomesAuthoritative() {
        UUID campaignId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        CampaignRecipient bound = new CampaignRecipient(
                campaignId,
                CampaignRecipientKey.forUnresolvedName("OriginalName"),
                2,
                "OriginalName",
                playerId,
                CampaignRecipientState.PENDING_OFFLINE,
                null,
                null,
                null,
                0,
                null,
                null,
                2_000L);

        assertEquals("name:originalname", bound.recipientKey().value());
        assertEquals(playerId, bound.playerId());
        assertEquals("OriginalName", bound.originalValue());
    }
}
