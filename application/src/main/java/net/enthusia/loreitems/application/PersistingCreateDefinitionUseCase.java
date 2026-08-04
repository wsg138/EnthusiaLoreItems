package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class PersistingCreateDefinitionUseCase implements CreateDefinitionUseCase {
    private static final TemplateRevision INITIAL_REVISION = new TemplateRevision(1);
    private static final String AGGREGATE_TYPE = "lore_definition";
    private static final String EVENT_TYPE = "definition_created";
    private static final String ACTOR_TYPE = "player";

    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final Supplier<UUID> definitionIdSupplier;

    public PersistingCreateDefinitionUseCase(UnitOfWork unitOfWork, Clock clock) {
        this(unitOfWork, clock, UUID::randomUUID);
    }

    PersistingCreateDefinitionUseCase(
            UnitOfWork unitOfWork,
            Clock clock,
            Supplier<UUID> definitionIdSupplier) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.definitionIdSupplier = Objects.requireNonNull(
                definitionIdSupplier, "definitionIdSupplier");
    }

    @Override
    public CompletionStage<CreateDefinitionResult> create(
            CreateDefinitionRequest request) {
        Objects.requireNonNull(request, "request");
        long createdAt = clock.millis();
        LoreDefinitionId definitionId = new LoreDefinitionId(
                Objects.requireNonNull(definitionIdSupplier.get(), "generated definition ID"));
        LoreDefinition definition = new LoreDefinition(
                definitionId,
                request.key(),
                request.displayName(),
                INITIAL_REVISION,
                createdAt,
                null);
        LoreDefinitionRevision revision = new LoreDefinitionRevision(
                definitionId,
                INITIAL_REVISION,
                request.template().codecVersion(),
                request.template().payload(),
                createdAt);

        return unitOfWork.execute(context -> {
            if (!context.definitions().create(definition, revision)) {
                return CreateDefinitionResult.activeKeyExists();
            }
            context.audit().append(AuditEventRecord.pending(
                    AGGREGATE_TYPE,
                    definitionId.value().toString(),
                    EVENT_TYPE,
                    ACTOR_TYPE,
                    request.actorId().toString(),
                    creationDetail(request),
                    createdAt));
            return CreateDefinitionResult.created(definitionId);
        });
    }

    private static String creationDetail(CreateDefinitionRequest request) {
        return "{\"key\":\"" + request.key().value()
                + "\",\"revision\":" + INITIAL_REVISION.value() + '}';
    }
}
