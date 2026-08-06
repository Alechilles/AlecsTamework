package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentConfigIndex;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentNpcSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentResolution;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAttachmentEvaluationSystemTest {
    @Test
    void shouldEvaluateRoleReturnsFalseWhenIndexHasNoRulesForRole() {
        assertFalse(DynamicAttachmentEvaluationSystem.shouldEvaluateRole(
                "Moose",
                DynamicAttachmentConfigIndex.empty()
        ));
    }

    @Test
    void fingerprintForTestIsStableForEquivalentSnapshots() {
        DynamicAttachmentNpcSnapshot first = baseSnapshot().build();
        DynamicAttachmentNpcSnapshot second = baseSnapshot().build();

        assertEquals(
                DynamicAttachmentEvaluationSystem.fingerprintForTest(first),
                DynamicAttachmentEvaluationSystem.fingerprintForTest(second)
        );
    }

    @Test
    void fingerprintForTestChangesWhenRelevantSnapshotFieldsChange() {
        DynamicAttachmentEvaluationSystem.DynamicAttachmentFingerprint baseline =
                DynamicAttachmentEvaluationSystem.fingerprintForTest(baseSnapshot().build());

        assertNotEquals(baseline, DynamicAttachmentEvaluationSystem.fingerprintForTest(
                baseSnapshot().displayName("Birch").build()
        ));
        assertNotEquals(baseline, DynamicAttachmentEvaluationSystem.fingerprintForTest(
                baseSnapshot().owner(UUID.fromString("00000000-0000-0000-0000-000000000222"), "Alec").build()
        ));
        assertNotEquals(baseline, DynamicAttachmentEvaluationSystem.fingerprintForTest(
                baseSnapshot().need("hunger", 0.5).build()
        ));
        assertNotEquals(baseline, DynamicAttachmentEvaluationSystem.fingerprintForTest(
                baseSnapshot().trait("brave", 3.0).build()
        ));
        assertNotEquals(baseline, DynamicAttachmentEvaluationSystem.fingerprintForTest(
                baseSnapshot().commandState("has_home", "false").build()
        ));
    }

    @Test
    void restoreUnconfiguredRoleClearsStaleTemporaryOverlay() {
        TameworkAttachmentsComponent stored = new TameworkAttachmentsComponent(
                "cfg",
                Map.of("blanket", "Blanket_Canada")
        );
        TameworkDynamicAttachmentsComponent overlay = new TameworkDynamicAttachmentsComponent(
                new TameworkDynamicAttachmentsComponent.ActiveSlot[] {
                        new TameworkDynamicAttachmentsComponent.ActiveSlot(
                                "blanket",
                                "Blanket_Red",
                                true,
                                "Blanket_Canada",
                                "cfg/hungry"
                        )
                }
        );

        var result = DynamicAttachmentEvaluationSystem.restoreUnconfiguredRoleForTest(stored, overlay);

        assertTrue(result.changed());
        assertEquals(Map.of("blanket", "Blanket_Red"), result.attachments().getAttachmentIds());
        assertEquals(0, result.overlay().getActiveSlots().length);
    }

    @Test
    void filterResolutionDropsUnsupportedAttachmentSelections() {
        DynamicAttachmentResolution resolution = new DynamicAttachmentResolution(
                Map.of("blanket", "Blanket_Canada", "saddle", "Saddle_Red"),
                Map.of(
                        "badge",
                        new DynamicAttachmentResolution.TemporaryAttachment("Badge_Gold", "cfg/badge"),
                        "collar",
                        new DynamicAttachmentResolution.TemporaryAttachment("Collar_Red", "cfg/collar")
                )
        );

        DynamicAttachmentResolution filtered = DynamicAttachmentEvaluationSystem.filterResolutionForTest(
                resolution,
                Map.of(
                        "blanket", Set.of("Blanket_Canada"),
                        "badge", Set.of("Badge_Gold")
                )
        );

        assertEquals(Map.of("blanket", "Blanket_Canada"), filtered.permanentAttachments());
        assertEquals(Set.of("badge"), filtered.temporaryAttachments().keySet());
        assertEquals("Badge_Gold", filtered.temporaryAttachments().get("badge").value());
        assertEquals("cfg/badge", filtered.temporaryAttachments().get("badge").ruleKey());
    }

    @Test
    void filterResolutionReturnsEmptyWhenModelOptionsAreUnavailable() {
        DynamicAttachmentResolution resolution = new DynamicAttachmentResolution(
                Map.of("blanket", "Blanket_Canada"),
                Map.of("badge", new DynamicAttachmentResolution.TemporaryAttachment("Badge_Gold", "cfg/badge"))
        );

        assertTrue(DynamicAttachmentEvaluationSystem.filterResolutionForTest(resolution, Map.of()).isEmpty());
        assertTrue(DynamicAttachmentEvaluationSystem.filterResolutionForTest(resolution, null).isEmpty());
    }

    private static DynamicAttachmentNpcSnapshot.Builder baseSnapshot() {
        return DynamicAttachmentNpcSnapshot.builder()
                .roleId("Moose")
                .displayName("Aspen")
                .owner(UUID.fromString("00000000-0000-0000-0000-000000000111"), "Alec")
                .tamed(true)
                .gender("Female")
                .lifeStage("Adult")
                .happiness(0.75)
                .needs(Map.of("hunger", 0.8, "thirst", 0.6))
                .traits(Map.of("brave", 2.0, "patient", 1.0))
                .commandStates(Map.of("has_home", "true", "linked_tool_count", "1"));
    }
}
