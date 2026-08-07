package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class DistributionCampaignStartRequestTest {
    private static final long CREATED_AT = 1_000L;

    @Test
    void recipientSnapshotIsCopiedAndCannotChangeAfterStartRequestCreation() {
        DistributionCampaign campaign = draftCampaign();
        List<CampaignRecipient> source = new ArrayList<>();
        source.add(CampaignRecipient.unresolvedName(
                campaign.campaignId(), 0, "PlayerOne", CREATED_AT));

        DistributionCampaignStartRequest request = new DistributionCampaignStartRequest(
                campaign, source, " PLAYER ", " operator ");
        source.clear();

        assertEquals(1, request.recipients().size());
        assertEquals("PLAYER", request.actorType());
        assertEquals("operator", request.actorId());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.recipients().clear());
    }

    @Test
    void caseOnlyDuplicateRecipientKeysAreRejected() {
        DistributionCampaign campaign = draftCampaign();
        List<CampaignRecipient> recipients = List.of(
                CampaignRecipient.unresolvedName(
                        campaign.campaignId(), 0, "ExamplePlayer", CREATED_AT),
                CampaignRecipient.unresolvedName(
                        campaign.campaignId(), 1, "exampleplayer", CREATED_AT));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DistributionCampaignStartRequest(
                        campaign, recipients, "PLAYER", "operator"));
    }

    @Test
    void recipientSnapshotIndexesMustRemainContiguous() {
        DistributionCampaign campaign = draftCampaign();
        CampaignRecipient recipient = CampaignRecipient.unresolvedName(
                campaign.campaignId(), 1, "PlayerOne", CREATED_AT);

        assertThrows(
                IllegalArgumentException.class,
                () -> new DistributionCampaignStartRequest(
                        campaign, List.of(recipient), "PLAYER", "operator"));
    }

    private static DistributionCampaign draftCampaign() {
        return new DistributionCampaign(
                UUID.randomUUID(),
                "sha256:test",
                "group.yml",
                "Group",
                LoreDefinitionId.random(),
                new TemplateRevision(1),
                DistributionCampaignState.DRAFT,
                CREATED_AT,
                CREATED_AT,
                null);
    }
}
