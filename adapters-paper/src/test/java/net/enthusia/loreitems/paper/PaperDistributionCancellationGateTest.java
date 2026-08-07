package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PaperDistributionCancellationGateTest {
    private static final UUID CAMPAIGN_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void committedFenceDrainsHeldWorkIntoCancellation() {
        PaperDistributionCancellationGate gate = new PaperDistributionCancellationGate(2);
        CampaignRecipient claim = claimedRecipient();
        PreparedDistributionDelivery prepared = preparedDelivery();
        List<CampaignRecipient> cancelledClaims = new ArrayList<>();
        List<PreparedDistributionDelivery> cancelledPrepared = new ArrayList<>();

        gate.begin(CAMPAIGN_ID);
        assertEquals(PaperDistributionCancellationGate.Decision.HELD, gate.intercept(claim));
        assertEquals(PaperDistributionCancellationGate.Decision.HELD, gate.intercept(prepared));

        gate.committed(CAMPAIGN_ID, cancelledClaims::add, cancelledPrepared::add);

        assertEquals(List.of(claim), cancelledClaims);
        assertEquals(List.of(prepared), cancelledPrepared);
        assertEquals(PaperDistributionCancellationGate.Decision.CANCEL, gate.intercept(claim));
    }

    @Test
    void releasedFenceReturnsHeldWorkToNormalProcessing() {
        PaperDistributionCancellationGate gate = new PaperDistributionCancellationGate(2);
        CampaignRecipient claim = claimedRecipient();
        PreparedDistributionDelivery prepared = preparedDelivery();
        List<CampaignRecipient> processedClaims = new ArrayList<>();
        List<PreparedDistributionDelivery> processedPrepared = new ArrayList<>();

        gate.begin(CAMPAIGN_ID);
        gate.intercept(claim);
        gate.intercept(prepared);
        gate.release(CAMPAIGN_ID, processedClaims::add, processedPrepared::add);

        assertEquals(List.of(claim), processedClaims);
        assertEquals(List.of(prepared), processedPrepared);
        assertEquals(PaperDistributionCancellationGate.Decision.PROCESS, gate.intercept(claim));
    }

    @Test
    void heldWorkNeverExceedsConfiguredPerCampaignBound() {
        PaperDistributionCancellationGate gate = new PaperDistributionCancellationGate(1);
        CampaignRecipient claim = claimedRecipient();
        gate.begin(CAMPAIGN_ID);

        assertEquals(PaperDistributionCancellationGate.Decision.HELD, gate.intercept(claim));
        assertEquals(
                PaperDistributionCancellationGate.Decision.LEASE_EXPIRY_FALLBACK,
                gate.intercept(claim));
    }

    private static CampaignRecipient claimedRecipient() {
        return new CampaignRecipient(
                CAMPAIGN_ID,
                net.enthusia.loreitems.domain.CampaignRecipientKey.forPlayer(PLAYER_ID),
                0,
                PLAYER_ID.toString(),
                PLAYER_ID,
                CampaignRecipientState.RESERVED_IN_FLIGHT,
                null,
                "claim-token",
                2_000L,
                1,
                null,
                null,
                1_000L);
    }

    private static PreparedDistributionDelivery preparedDelivery() {
        return new PreparedDistributionDelivery(
                CAMPAIGN_ID,
                net.enthusia.loreitems.domain.CampaignRecipientKey.forPlayer(PLAYER_ID),
                new LoreInstanceId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                new LoreDefinitionId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
                PLAYER_ID,
                new TemplateRevision(1L),
                new EncodedItemTemplate(1, new byte[] {1}),
                "claim-token",
                2_000L,
                1,
                1_000L);
    }
}
