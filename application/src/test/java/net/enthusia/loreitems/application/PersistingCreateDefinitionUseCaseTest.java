package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PersistingCreateDefinitionUseCaseTest {
    private static final UUID DEFINITION_UUID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR_UUID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long CREATED_AT = 12_345L;

    @Test
    void persistsInitialDefinitionRevisionAndAuditAsOneUseCase() {
        RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork(true);
        PersistingCreateDefinitionUseCase useCase = useCase(unitOfWork);
        CreateDefinitionRequest request = request();

        CreateDefinitionResult result = useCase.create(request).toCompletableFuture().join();

        assertEquals(CreateDefinitionStatus.CREATED, result.status());
        assertEquals(new LoreDefinitionId(DEFINITION_UUID), result.definitionId());
        LoreDefinition definition = unitOfWork.definition;
        LoreDefinitionRevision revision = unitOfWork.revision;
        assertEquals(request.key(), definition.key());
        assertEquals("Vanguard's Hourglass", definition.displayName());
        assertEquals(1L, definition.currentRevision().value());
        assertEquals(CREATED_AT, definition.createdAtEpochMillis());
        assertEquals(definition.id(), revision.definitionId());
        assertEquals(7, revision.codecVersion());
        assertArrayEquals(new byte[] {4, 5, 6}, revision.templateBlob());
        assertEquals(1, unitOfWork.auditEvents.size());
        AuditEventRecord audit = unitOfWork.auditEvents.getFirst();
        assertEquals("definition_created", audit.eventType());
        assertEquals(ACTOR_UUID.toString(), audit.actorId());
        assertEquals("{\"key\":\"vanguards_hourglass\",\"revision\":1}", audit.detailJson());
    }

    @Test
    void reportsActiveKeyConflictWithoutAppendingAudit() {
        RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork(false);
        CreateDefinitionResult result = useCase(unitOfWork)
                .create(request())
                .toCompletableFuture()
                .join();

        assertEquals(CreateDefinitionStatus.ACTIVE_KEY_EXISTS, result.status());
        assertNull(result.definitionId());
        assertEquals(0, unitOfWork.auditEvents.size());
    }

    private static PersistingCreateDefinitionUseCase useCase(UnitOfWork unitOfWork) {
        return new PersistingCreateDefinitionUseCase(
                unitOfWork,
                Clock.fixed(Instant.ofEpochMilli(CREATED_AT), ZoneOffset.UTC),
                () -> DEFINITION_UUID);
    }

    private static CreateDefinitionRequest request() {
        return new CreateDefinitionRequest(
                new DefinitionKey("Vanguards_Hourglass"),
                "  Vanguard's Hourglass  ",
                new EncodedItemTemplate(7, new byte[] {4, 5, 6}),
                ACTOR_UUID);
    }

    private static final class RecordingUnitOfWork implements UnitOfWork {
        private final boolean createResult;
        private final List<AuditEventRecord> auditEvents = new ArrayList<>();
        private LoreDefinition definition;
        private LoreDefinitionRevision revision;

        private RecordingUnitOfWork(boolean createResult) {
            this.createResult = createResult;
        }

        @Override
        public <T> CompletionStage<T> execute(Work<T> work) {
            try {
                Context context = new Context() {
                    @Override
                    public DefinitionMutations definitions() {
                        return new DefinitionMutations() {
                            @Override
                            public boolean create(
                                    LoreDefinition candidate,
                                    LoreDefinitionRevision initialRevision) {
                                definition = candidate;
                                revision = initialRevision;
                                return createResult;
                            }

                            @Override
                            public boolean markDeleted(
                                    LoreDefinitionId definitionId,
                                    TemplateRevision expectedCurrentRevision,
                                    Instant deletedAt) {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override
                    public DeletedDefinitionMarkerMutations deletedDefinitionMarkers() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public AuditAppender audit() {
                        return event -> {
                            auditEvents.add(event);
                            return event;
                        };
                    }
                };
                return CompletableFuture.completedFuture(work.execute(context));
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
    }
}
