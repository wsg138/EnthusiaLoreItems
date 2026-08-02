package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;

public interface AuditRepository {
    CompletionStage<AuditEventRecord> append(AuditEventRecord event);

    CompletionStage<Page<AuditEventRecord>> listByAggregate(
            String aggregateType, String aggregateId, PageRequest request);
}
