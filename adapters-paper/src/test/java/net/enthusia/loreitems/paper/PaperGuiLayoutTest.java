package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.junit.jupiter.api.Test;

class PaperGuiLayoutTest {
    @Test
    void trackingNavigationUsesDedicatedBackAndCenteredPagingControls() {
        assertEquals(45, PaperTrackingAdministrationItems.BACK);
        assertEquals(48, PaperTrackingAdministrationItems.PREVIOUS);
        assertEquals(49, PaperTrackingAdministrationItems.STATUS);
        assertEquals(50, PaperTrackingAdministrationItems.NEXT);
    }

    @Test
    void evidenceViewKeepsItsExactParentInstancePage() {
        LoreDefinitionId definitionId = LoreDefinitionId.random();
        LoreInstanceId instanceId = LoreInstanceId.random();

        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.evidence(
                definitionId,
                4,
                instanceId,
                2,
                true,
                List.of(),
                null);

        assertEquals(definitionId, view.definitionId);
        assertEquals(instanceId, view.instanceId);
        assertEquals(4, view.parentPageNumber);
        assertEquals(2, view.pageNumber);
    }

    @Test
    void templateManagementAndPreviewActionsAreSymmetrical() {
        assertEquals(38, PaperTemplateEditorRenderer.MANAGEMENT_PURGE);
        assertEquals(42, PaperTemplateEditorRenderer.MANAGEMENT_DELETE);
        assertEquals(45, PaperTemplateEditorRenderer.MANAGEMENT_BACK);
        assertEquals(53, PaperTemplateEditorRenderer.MANAGEMENT_REFRESH);
        assertEquals(45, PaperTemplateEditorRenderer.PREVIEW_BACK);
        assertEquals(49, PaperTemplateEditorRenderer.PREVIEW_CONFIRM);
        assertEquals(53, PaperTemplateEditorRenderer.PREVIEW_CANCEL);
    }

    @Test
    void editorActionsUseThreeCenteredRows() {
        for (int slot = 10; slot <= 16; slot++) {
            assertNotNull(PaperTemplateEditorRenderer.action(slot));
        }
        for (int slot = 19; slot <= 25; slot++) {
            assertNotNull(PaperTemplateEditorRenderer.action(slot));
        }
        for (int slot = 29; slot <= 33; slot++) {
            assertNotNull(PaperTemplateEditorRenderer.action(slot));
        }
        assertNull(PaperTemplateEditorRenderer.action(9));
        assertNull(PaperTemplateEditorRenderer.action(17));
        assertNull(PaperTemplateEditorRenderer.action(18));
        assertNull(PaperTemplateEditorRenderer.action(26));
        assertNull(PaperTemplateEditorRenderer.action(28));
        assertNull(PaperTemplateEditorRenderer.action(34));
    }
}
