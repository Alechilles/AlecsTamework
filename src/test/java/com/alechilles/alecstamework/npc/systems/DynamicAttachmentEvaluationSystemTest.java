package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentConfigIndex;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentNpcSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    void sourceUsesCommandBufferWritesAndDoesNotMutateStoreDirectly() throws IOException {
        String source = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "systems",
                "DynamicAttachmentEvaluationSystem.java"
        ));

        assertTrue(source.contains("commandBuffer.putComponent("));
        assertFalse(source.contains("store.putComponent("));
        assertFalse(source.contains("store.removeComponent("));
        assertFalse(source.contains("store.tryRemoveComponent("));
        assertFalse(source.contains("store.addComponent("));
    }

    @Test
    void tameworkRegistersDynamicAttachmentEvaluationSystem() throws IOException {
        String source = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "Tamework.java"
        ));

        assertTrue(source.contains("new DynamicAttachmentEvaluationSystem("));
    }

    private static DynamicAttachmentNpcSnapshot.Builder baseSnapshot() {
        return DynamicAttachmentNpcSnapshot.builder()
                .roleId("Moose")
                .displayName("Aspen")
                .ownerPresent(true)
                .tamed(true)
                .gender("Female")
                .lifeStage("Adult")
                .happiness(0.75)
                .needs(Map.of("hunger", 0.8, "thirst", 0.6))
                .traits(Map.of("brave", 2.0, "patient", 1.0))
                .commandStates(Map.of("has_home", "true", "linked_tool_count", "1"));
    }
}
