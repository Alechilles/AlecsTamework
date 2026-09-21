package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HytaleDirectLiveCoopEvidenceFactoryTest {
    // Assigning a coop slot used to discard absolute health, hiding it on stored cards.
    @Test
    void assigningCoopSlotPreservesHealthInSavedEvidence() {
        var source = new CoopResidentStateSnapshot(UUID.randomUUID(), null, -1, "Chicken",
                null, null, null, null, null, null, null, null, null, null, null, null,
                40.0, 100.0, 40.0, 123L);
        var assigned = HytaleDirectLiveCoopEvidenceFactory.withSlot(source, "coop", 2);
        var codec = new CoopResidentStateSnapshotCodec();
        var saved = codec.copy(assigned);
        assertEquals(40.0, saved.currentHealth());
        assertEquals(100.0, saved.maximumHealth());
        assertEquals("coop", saved.coopId());
        assertEquals(2, saved.residentSlot());
    }
}
