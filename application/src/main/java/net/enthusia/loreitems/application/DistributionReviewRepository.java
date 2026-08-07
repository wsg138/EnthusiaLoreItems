package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.CampaignRecipient;

/** Bounded global view of campaign recipients that require operator review. */
public interface DistributionReviewRepository {
    CompletionStage<Page<CampaignRecipient>> listReviewRequired(PageRequest request);
}
