package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PersistingDisplayItemObservationUseCaseTest {
    private static final long NOW = 12_345L;

    @Test
    void delegatesValidatedRequestAtClockInstant() {
        RecordingStore store = new RecordingStore();
        PersistingDisplayItemObservationUseCase useCase =
                new PersistingDisplayItemObservationUseCase(
                        store,
                        Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        DisplayItemObservationUseCase.Request request = new DisplayItemObservationUseCase.Request(
                new LoreItemIdentity(
                        new LoreDefinitionId(UUID.fromString(
                                "11111111-1111-1111-1111-111111111111")),
                        new LoreInstanceId(UUID.fromString(
                                "22222222-2222-2222-2222-222222222222")),
                        new TemplateRevision(4)),
                new LocationDescriptor(
                        LocationDescriptor.Type.ITEM_FRAME,
                        "minecraft:overworld:1:64:2:33333333-3333-3333-3333-333333333333",
                        "item"),
                DisplayItemObservationUseCase.Presence.PRESENT,
                "item-frame-change");

        DisplayItemObservationUseCase.Result result = useCase.record(request)
                .toCompletableFuture().join();

        assertSame(request, store.request);
        assertEquals(NOW, store.observedAt.toEpochMilli());
        assertEquals(DisplayItemObservationUseCase.Status.RECORDED, result.status());
    }

    private static final class RecordingStore implements DisplayItemObservationStore {
        private DisplayItemObservationUseCase.Request request;
        private Instant observedAt;

        @Override
        public CompletionStage<DisplayItemObservationUseCase.Result> record(
                DisplayItemObservationUseCase.Request candidate,
                Instant candidateObservedAt) {
            request = candidate;
            observedAt = candidateObservedAt;
            return CompletableFuture.completedFuture(DisplayItemObservationUseCase.Result.of(
                    DisplayItemObservationUseCase.Status.RECORDED,
                    "Recorded."));
        }
    }
}
