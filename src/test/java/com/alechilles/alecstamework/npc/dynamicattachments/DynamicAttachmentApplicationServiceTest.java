package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAttachmentApplicationServiceTest {
    @Test
    void permanentMergePreservesOtherSlotsAndOverwritesWinningSlots() {
        Map<String, String> merged = DynamicAttachmentApplicationService.mergePermanent(
                Map.of("head", "cap", "tail", "plain_tail"),
                Map.of("head", "crown", "neck", "collar")
        );

        assertEquals(Map.of("head", "crown", "tail", "plain_tail", "neck", "collar"), merged);
        assertThrows(UnsupportedOperationException.class, () -> merged.put("back", "blanket"));
    }

    @Test
    void whileMatchingCapturesPreviousValueAndRestoresItAfterInactive() {
        DynamicAttachmentApplicationService.OverlayMerge applied = DynamicAttachmentApplicationService.mergeTemporary(
                Map.of("head", "cap"),
                emptyOverlay(),
                Map.of("head", temporary("crown", "rule:happy"))
        );

        assertEquals(Map.of("head", "crown"), applied.attachments());
        TameworkDynamicAttachmentsComponent.ActiveSlot active = applied.overlay().getActiveSlots()[0];
        assertEquals("head", active.getSlot());
        assertEquals("cap", active.getPreviousValue());
        assertTrue(active.isHasPreviousValue());
        assertEquals("crown", active.getAppliedValue());
        assertEquals("rule:happy", active.getRuleKey());

        DynamicAttachmentApplicationService.OverlayMerge restored =
                DynamicAttachmentApplicationService.restoreInactiveTemporarySlots(
                        applied.attachments(),
                        applied.overlay(),
                        Map.of()
                );

        assertEquals(Map.of("head", "cap"), restored.attachments());
        assertEquals(0, restored.overlay().getActiveSlots().length);
    }

    @Test
    void absentPreviousSlotIsRemovedOnRestore() {
        DynamicAttachmentApplicationService.OverlayMerge applied = DynamicAttachmentApplicationService.mergeTemporary(
                Map.of(),
                emptyOverlay(),
                Map.of("aura", temporary("sparkle", "rule:glow"))
        );

        DynamicAttachmentApplicationService.OverlayMerge restored =
                DynamicAttachmentApplicationService.restoreInactiveTemporarySlots(
                        applied.attachments(),
                        applied.overlay(),
                        Map.of()
                );

        assertTrue(restored.attachments().isEmpty());
        assertEquals(0, restored.overlay().getActiveSlots().length);
    }

    @Test
    void externalChangeGuardDoesNotOverwriteChangedSlotButClearsStaleOverlay() {
        TameworkDynamicAttachmentsComponent overlay = overlay(
                slot("head", "cap", true, "crown", "rule:happy")
        );

        DynamicAttachmentApplicationService.OverlayMerge restored =
                DynamicAttachmentApplicationService.restoreInactiveTemporarySlots(
                        Map.of("head", "helmet"),
                        overlay,
                        Map.of()
                );

        assertEquals(Map.of("head", "helmet"), restored.attachments());
        assertEquals(0, restored.overlay().getActiveSlots().length);
    }

    @Test
    void activeTempWithSameRuleRetainsOriginalPreviousValueAcrossRepeatedApply() {
        TameworkDynamicAttachmentsComponent overlay = overlay(
                slot("head", "cap", true, "crown", "rule:happy")
        );

        DynamicAttachmentApplicationService.OverlayMerge applied = DynamicAttachmentApplicationService.mergeTemporary(
                Map.of("head", "crown"),
                overlay,
                Map.of("head", temporary("crown", "rule:happy"))
        );

        TameworkDynamicAttachmentsComponent.ActiveSlot active = applied.overlay().getActiveSlots()[0];
        assertEquals("cap", active.getPreviousValue());
        assertTrue(active.isHasPreviousValue());
        assertEquals(Map.of("head", "crown"), applied.attachments());
    }

    @Test
    void applyResolutionReportsUnchangedForNoOpEmptyResolutionAndEmptyOverlay() {
        TameworkAttachmentsComponent stored = new TameworkAttachmentsComponent("cfg", Map.of());
        DynamicAttachmentResolution resolution = new DynamicAttachmentResolution(Map.of(), Map.of());

        DynamicAttachmentApplicationService.ApplyResult result = DynamicAttachmentApplicationService.applyResolution(
                stored,
                emptyOverlay(),
                resolution
        );

        assertFalse(result.changed());
        assertEquals("cfg", result.attachments().getConfigId());
        assertTrue(result.attachments().getAttachmentIds().isEmpty());
        assertEquals(0, result.overlay().getActiveSlots().length);
        assertNotSame(stored, result.attachments());
    }

    @Test
    void applyResolutionRestoresStaleThenAppliesPermanentThenTemporary() {
        TameworkAttachmentsComponent stored = new TameworkAttachmentsComponent(
                "cfg",
                Map.of("head", "crown", "tail", "plain_tail")
        );
        TameworkDynamicAttachmentsComponent overlay = overlay(
                slot("head", "cap", true, "crown", "rule:old")
        );
        DynamicAttachmentResolution resolution = new DynamicAttachmentResolution(
                Map.of("head", "helmet", "neck", "collar"),
                Map.of("tail", temporary("ribbon", "rule:new"))
        );

        DynamicAttachmentApplicationService.ApplyResult result =
                DynamicAttachmentApplicationService.applyResolution(stored, overlay, resolution);

        assertTrue(result.changed());
        assertEquals("cfg", result.attachments().getConfigId());
        assertEquals(
                Map.of("head", "helmet", "neck", "collar", "tail", "ribbon"),
                result.attachments().getAttachmentIds()
        );
        TameworkDynamicAttachmentsComponent.ActiveSlot active = result.overlay().getActiveSlots()[0];
        assertEquals("tail", active.getSlot());
        assertEquals("plain_tail", active.getPreviousValue());
        assertTrue(active.isHasPreviousValue());
        assertEquals("ribbon", active.getAppliedValue());
        assertEquals("rule:new", active.getRuleKey());
    }

    @Test
    void filterSupportedSelectionsDropsUnsupportedSlots() {
        Map<String, String> filtered = DynamicAttachmentApplicationService.filterSupportedSelections(
                Map.of("head", "crown", "tail", "ribbon", "neck", "collar"),
                Map.of("head", Set.of("crown"), "tail", Set.of("bow"))
        );

        assertEquals(Map.of("head", "crown"), filtered);
    }

    private static TameworkDynamicAttachmentsComponent emptyOverlay() {
        return new TameworkDynamicAttachmentsComponent(null);
    }

    private static TameworkDynamicAttachmentsComponent overlay(TameworkDynamicAttachmentsComponent.ActiveSlot... slots) {
        return new TameworkDynamicAttachmentsComponent(slots);
    }

    private static TameworkDynamicAttachmentsComponent.ActiveSlot slot(String slot,
                                                                       String previousValue,
                                                                       boolean hasPreviousValue,
                                                                       String appliedValue,
                                                                       String ruleKey) {
        return new TameworkDynamicAttachmentsComponent.ActiveSlot(
                slot,
                previousValue,
                hasPreviousValue,
                appliedValue,
                ruleKey
        );
    }

    private static DynamicAttachmentResolution.TemporaryAttachment temporary(String value, String ruleKey) {
        return new DynamicAttachmentResolution.TemporaryAttachment(value, ruleKey);
    }
}
