package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.UnitOfWork;
import net.enthusia.loreitems.domain.DeletedDefinitionMarker;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteUnitOfWork implements UnitOfWork {
    private final SQLiteStorageRuntime storage;
    private final ThreadLocal<Boolean> transactionActive = new ThreadLocal<>();

    public SQLiteUnitOfWork(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public <T> CompletionStage<T> execute(Work<T> work) {
        Objects.requireNonNull(work, "work");
        if (Boolean.TRUE.equals(transactionActive.get())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Nested unit-of-work execution is not supported"));
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(connection, transaction -> {
            transactionActive.set(Boolean.TRUE);
            SQLiteContext context = new SQLiteContext(transaction);
            try {
                return work.execute(context);
            } finally {
                context.close();
                transactionActive.remove();
            }
        }));
    }

    private static final class SQLiteContext implements UnitOfWork.Context {
        private final Connection connection;
        private final UnitOfWork.DefinitionMutations definitionMutations =
                new UnitOfWork.DefinitionMutations() {
                    @Override
                    public boolean create(
                            LoreDefinition definition,
                            LoreDefinitionRevision initialRevision) throws Exception {
                        return createDefinition(definition, initialRevision);
                    }

                    @Override
                    public boolean markDeleted(
                            LoreDefinitionId definitionId,
                            TemplateRevision expectedCurrentRevision,
                            Instant deletedAt) throws Exception {
                        return SQLiteContext.this.markDeleted(
                                definitionId, expectedCurrentRevision, deletedAt);
                    }
                };
        private final UnitOfWork.DeletedDefinitionMarkerMutations markerMutations =
                this::createDeletedDefinitionMarker;
        private final UnitOfWork.AuditAppender auditAppender = this::appendAudit;
        private boolean active = true;

        private SQLiteContext(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        @Override
        public UnitOfWork.DefinitionMutations definitions() {
            ensureActive();
            return definitionMutations;
        }

        @Override
        public UnitOfWork.DeletedDefinitionMarkerMutations deletedDefinitionMarkers() {
            ensureActive();
            return markerMutations;
        }

        @Override
        public UnitOfWork.AuditAppender audit() {
            ensureActive();
            return auditAppender;
        }

        private boolean createDefinition(
                LoreDefinition definition,
                LoreDefinitionRevision initialRevision) throws Exception {
            ensureActive();
            return SQLiteDefinitionRepository.createInTransaction(
                    connection, definition, initialRevision);
        }

        private boolean markDeleted(
                LoreDefinitionId definitionId,
                TemplateRevision expectedCurrentRevision,
                Instant deletedAt) throws Exception {
            ensureActive();
            return SQLiteDefinitionRepository.markDeletedInTransaction(
                    connection, definitionId, expectedCurrentRevision, deletedAt);
        }

        private void createDeletedDefinitionMarker(DeletedDefinitionMarker marker)
                throws Exception {
            ensureActive();
            SQLiteDeletedDefinitionMarkerRepository.createInTransaction(connection, marker);
        }

        private AuditEventRecord appendAudit(AuditEventRecord event) throws Exception {
            ensureActive();
            return SQLiteAuditRepository.appendInTransaction(connection, event);
        }

        private void close() {
            active = false;
        }

        private void ensureActive() {
            if (!active) {
                throw new IllegalStateException(
                        "The unit-of-work context is valid only while its callback is running");
            }
        }
    }
}
