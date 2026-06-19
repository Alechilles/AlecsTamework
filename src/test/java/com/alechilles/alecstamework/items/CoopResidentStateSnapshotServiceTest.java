package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.RemoveReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class CoopResidentStateSnapshotServiceTest {

    @Test
    void unloadRemovalDoesNotResolveCoopContext() {
        CoopResidentStateSnapshotService service = new CoopResidentStateSnapshotService();

        assertNull(service.onNpcRemoved(null, RemoveReason.UNLOAD, null));
    }
}
