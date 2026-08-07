package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.OperationView;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class DestructiveAdministrationOperationViewTest {
    @Test
    void rejectsOverflowingTerminalCountSum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> operation(Long.MAX_VALUE, Long.MAX_VALUE, 1L));
    }

    @Test
    void acceptsTerminalCountsExactlyEqualToTarget() {
        OperationView operation = operation(
                Long.MAX_VALUE,
                Long.MAX_VALUE - 1L,
                1L);

        assertEquals(0L, operation.remainingCount());
    }

    private static OperationView operation(
            long targetCount,
            long completedCount,
            long abortedCount) {
        return new OperationView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                DestructiveOperationType.PURGE_DEFINITION,
                new LoreDefinitionId(UUID.fromString(
                        "22222222-2222-2222-2222-222222222222")),
                null,
                new TemplateRevision(1L),
                DestructiveOperationState.ACTIVE,
                "test-actor",
                "test-idempotency-key",
                targetCount,
                0L,
                0L,
                0L,
                completedCount,
                abortedCount,
                1L,
                1L,
                null);
    }
}
