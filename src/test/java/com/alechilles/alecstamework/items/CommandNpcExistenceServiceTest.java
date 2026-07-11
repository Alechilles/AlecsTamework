package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for scan-free loaded-NPC existence probes. */
class CommandNpcExistenceServiceTest {
    private static final UUID NPC_UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void injectedIndexPreservesUnknownAbsentAndLiveStates() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        CommandNpcExistenceService service = new CommandNpcExistenceService(index);

        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, service.probe(NPC_UUID).status());
        assertFalse(service.isKnownLive(NPC_UUID));

        index.markInitializationComplete();
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, service.probe(NPC_UUID).status());
        assertFalse(service.isKnownLive(NPC_UUID));

        index.recordAdded(NPC_UUID, new LoadedNpcIdentityIndex.Location("world-a", "store-a"));
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, service.probe(NPC_UUID).status());
        assertTrue(service.isKnownLive(NPC_UUID));

        index.recordAdded(NPC_UUID, new LoadedNpcIdentityIndex.Location("world-b", "store-b"));
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.MULTIPLE_LOCATIONS, service.probe(NPC_UUID).status());
        assertTrue(service.isKnownLive(NPC_UUID));
    }

    @Test
    void nullProbeNeverClaimsLiveEvidence() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        CommandNpcExistenceService service = new CommandNpcExistenceService(index);

        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, service.probe(null).status());
        assertFalse(service.isKnownLive(null));

        index.markInitializationComplete();
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, service.probe(null).status());
        assertFalse(service.isKnownLive(null));
    }
}
