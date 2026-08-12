package com.alechilles.alecstamework.items;

import java.util.Set;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards exact completed source-section evidence for Recall recovery. */
class PendingRelocationSourceProbeTest {
    @Test
    void retainsOnlyExplicitlyCompletedSourceSections() {
        PendingRelocation pending = new PendingRelocation(
                UUID.randomUUID(),
                new Vector3d(10, 20, 30),
                "destination",
                new Vector3d(33, 65, -1),
                null,
                UUID.randomUUID(),
                true,
                true,
                null,
                null,
                0,
                0,
                true,
                null,
                null,
                true
        );

        pending.markSourceSectionLoaded("source", 1, 2, -1);
        pending.markSourceSectionLoaded("source", 1, 2, -1);

        Set<ImportedRecallRecoverySink.RecallSourceSection> probes =
                pending.completedSourceSections();
        assertEquals(1, probes.size());
        assertTrue(probes.contains(
                new ImportedRecallRecoverySink.RecallSourceSection(
                        "source", 1, 2, -1
                )
        ));
    }
}
