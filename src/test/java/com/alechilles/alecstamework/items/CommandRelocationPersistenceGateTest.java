package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.health.PersistenceCoverageRegistry;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityService;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityStatus;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRelocationPersistenceGateTest {
    private final PersistenceCoverageRegistry coverage = new PersistenceCoverageRegistry();
    private final PersistenceQuarantineRegistry quarantines = new PersistenceQuarantineRegistry();
    private final PersistenceFeatureCircuitRegistry circuits = new PersistenceFeatureCircuitRegistry();
    private final PersistenceScopeFactory scopes = new PersistenceScopeFactory(new byte[32]);
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void publishCoverage() {
        for (PersistenceEvidenceDimension dimension : PersistenceEvidenceDimension.values()) {
            coverage.publish(dimension, true, "test", 1L);
        }
    }

    @Test
    void profileQuarantineBlocksOnlyThatCompanionsRelocation() {
        CommandRelocationPersistenceGate gate = gate(npc -> "profile-a");
        quarantines.openImmediate(new PersistenceQuarantineRecord(
                "q", "incident", scopes.profile("profile-a"), PersistenceDomain.RECALL_RELOCATION,
                "relocation_under_verification", PersistenceQuarantineState.ACTIVE,
                "evidence", 1L, 1L, 1L, 0L, null));

        assertFalse(gate.decide(UUID.randomUUID(), null, ownerId,
                "destination", "source", "recall", false).allowed());
        assertTrue(gate(npc -> "profile-b").decide(UUID.randomUUID(), null, ownerId,
                "destination", "source", "recall", false).allowed());
    }

    @Test
    void featureCircuitAndMissingCanonicalIdentityFailBeforeQueueing() {
        circuits.publish(PersistenceDomain.RECALL_RELOCATION, false, "maintenance", 2L, "operator");
        var paused = gate(npc -> "profile-a").decide(UUID.randomUUID(), null, ownerId,
                "destination", null, "loaded_recall", true);
        var unresolved = gate(npc -> null).decide(UUID.randomUUID(), null, ownerId,
                "destination", null, "recall", false);

        assertEquals(PersistenceMutationAvailabilityStatus.FEATURE_PAUSED, paused.status());
        assertEquals(PersistenceMutationAvailabilityStatus.AUTHORITY_NOT_READY, unresolved.status());
        assertEquals("canonical_profile_unavailable_for_relocation", unresolved.reasonCode());
    }

    private CommandRelocationPersistenceGate gate(java.util.function.Function<UUID, String> loader) {
        return new CommandRelocationPersistenceGate(
                new PersistenceMutationAvailabilityService(
                        new PersistenceStorageHealthService(), quarantines, circuits, coverage),
                scopes,
                loader);
    }
}
