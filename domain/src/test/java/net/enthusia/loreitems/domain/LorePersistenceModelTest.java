package net.enthusia.loreitems.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LorePersistenceModelTest {
    @Test
    void definitionRevisionDefensivelyCopiesTemplateBytes() {
        byte[] source = {1, 2, 3};
        LoreDefinitionRevision revision = new LoreDefinitionRevision(
                new LoreDefinitionId(UUID.randomUUID()),
                new TemplateRevision(1),
                1,
                source,
                1_000L);

        source[0] = 9;
        byte[] returned = revision.templateBlob();
        returned[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, revision.templateBlob());
    }

    @Test
    void instanceRequiresMonotonicRevisionsAndMatchingTerminalState() {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());

        assertThrows(
                IllegalArgumentException.class,
                () -> new LoreInstance(
                        instanceId,
                        definitionId,
                        new TemplateRevision(2),
                        new TemplateRevision(1),
                        LoreInstanceLifecycle.ACTIVE,
                        1_000L,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoreInstance(
                        instanceId,
                        definitionId,
                        new TemplateRevision(1),
                        new TemplateRevision(1),
                        LoreInstanceLifecycle.VOID_DESTROYED,
                        1_000L,
                        null));

        LoreInstance terminal = new LoreInstance(
                instanceId,
                definitionId,
                new TemplateRevision(1),
                new TemplateRevision(1),
                LoreInstanceLifecycle.VOID_DESTROYED,
                1_000L,
                2_000L);
        assertEquals(2_000L, terminal.terminalAtEpochMillis());
    }

    @Test
    void lifecycleAllowsOnlyActiveToTerminalTransitions() {
        LoreInstanceLifecycle.ACTIVE.transitionTo(LoreInstanceLifecycle.REMOVED);

        assertThrows(
                IllegalStateException.class,
                () -> LoreInstanceLifecycle.REMOVED.transitionTo(
                        LoreInstanceLifecycle.VOID_DESTROYED));
        assertThrows(
                IllegalStateException.class,
                () -> LoreInstanceLifecycle.ACTIVE.transitionTo(LoreInstanceLifecycle.ACTIVE));
    }
}
