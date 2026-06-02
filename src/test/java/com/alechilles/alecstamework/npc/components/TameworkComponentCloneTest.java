package com.alechilles.alecstamework.npc.components;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests clone behavior for profiler-visible Tamework components. */
class TameworkComponentCloneTest {

    @Test
    void attachmentsClonePreservesSanitizedAttachmentMap() {
        TameworkAttachmentsComponent original = new TameworkAttachmentsComponent(
                "config",
                Map.of("head", "crest")
        );

        TameworkAttachmentsComponent clone = original.clone();

        assertEquals(original.getConfigId(), clone.getConfigId());
        assertEquals(original.getAttachmentIds(), clone.getAttachmentIds());
    }

    @Test
    void attachmentsMapCannotBeMutatedThroughGetter() {
        TameworkAttachmentsComponent component = new TameworkAttachmentsComponent(
                "config",
                Map.of("head", "crest")
        );

        assertThrows(UnsupportedOperationException.class,
                () -> component.getAttachmentIds().put("body", "coat"));
    }

    @Test
    void happinessCloneFastPathsEmptyImpulsesWithoutChangingState() {
        TameworkHappinessComponent original = new TameworkHappinessComponent("config", 0.7, -10L);

        TameworkHappinessComponent clone = original.clone();

        assertEquals("config", clone.getConfigId());
        assertEquals(0.7, clone.getValue(), 0.000001);
        assertEquals(-10L, clone.getLastUpdateMs());
        assertEquals(0, clone.getActiveImpulses().length);
    }
}
