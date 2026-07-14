package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Regression coverage for profile-aware linked-record list mutations. */
class LinkedNpcRecordCollectionTest {
    private final LinkedNpcRecordCollection collection = new LinkedNpcRecordCollection();

    @Test
    void selectedIdentityRepairLeavesUnrelatedDamagedRecordUntouched() {
        UUID selectedUuid = UUID.randomUUID();
        UUID replacementUuid = UUID.randomUUID();
        LinkedNpcRecord selected = record(selectedUuid, "profile-selected");
        LinkedNpcRecord unrelated = record(UUID.randomUUID(), "profile-conflicted");
        LinkedNpcRecord resolved = record(replacementUuid, "profile-selected");

        List<LinkedNpcRecord> repaired = collection.replaceResolvedSelection(
                List.of(selected, unrelated), selectedUuid, resolved);

        assertEquals(List.of(resolved, unrelated), repaired);
        assertSame(unrelated, repaired.get(1));
    }

    @Test
    void selectedIdentityRepairRemovesStaleDuplicateOfResolvedProfile() {
        UUID selectedUuid = UUID.randomUUID();
        LinkedNpcRecord selected = record(selectedUuid, null);
        LinkedNpcRecord staleDuplicate = record(UUID.randomUUID(), "profile-selected");
        LinkedNpcRecord resolved = record(UUID.randomUUID(), "profile-selected");

        List<LinkedNpcRecord> repaired = collection.replaceResolvedSelection(
                List.of(staleDuplicate, selected), selectedUuid, resolved);

        assertEquals(List.of(resolved), repaired);
    }

    private LinkedNpcRecord record(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(
                npcUuid, profileId, null, "default", null,
                null, null, "Tamed_Chicken", null, true, false, null);
    }
}
