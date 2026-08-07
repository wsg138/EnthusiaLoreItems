package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.readPage;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.selectColumns;

import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DistributionReviewRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;

/** SQLite-backed global queue view for campaign recipients requiring staff review. */
public final class SQLiteDistributionReviewRepository implements DistributionReviewRepository {
    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionReviewRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> listReviewRequired(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE state = 'REVIEW_REQUIRED' "
                            + "ORDER BY updated_at, campaign_id, snapshot_index "
                            + "LIMIT ? OFFSET ?")) {
                statement.setInt(1, request.limit() + 1);
                statement.setInt(2, request.offset());
                return readPage(statement, request);
            }
        });
    }
}
