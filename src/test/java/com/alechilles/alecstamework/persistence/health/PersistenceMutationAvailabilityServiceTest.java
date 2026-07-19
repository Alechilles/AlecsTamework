package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceMutationAvailabilityServiceTest {
    private final PersistenceStorageHealthService storage = new PersistenceStorageHealthService();
    private final PersistenceQuarantineRegistry quarantines = new PersistenceQuarantineRegistry();
    private final PersistenceFeatureCircuitRegistry circuits = new PersistenceFeatureCircuitRegistry();
    private boolean coverageReady = true;
    private final PersistenceMutationAvailabilityService service = new PersistenceMutationAvailabilityService(
            storage, quarantines, circuits, ignored -> coverageReady);

    @Test
    void profileQuarantineDoesNotBlockAnotherProfile() {
        quarantines.openImmediate(quarantine(scope(PersistenceScopeType.PROFILE, "profile-a")));

        assertEquals(PersistenceMutationAvailabilityStatus.QUARANTINED,
                service.decide(context(scope(PersistenceScopeType.PROFILE, "profile-a"))).status());
        assertTrue(service.decide(context(scope(PersistenceScopeType.PROFILE, "profile-b"))).allowed());
    }

    @Test
    void coopAndBreedingScopesRemainExact() {
        quarantines.openImmediate(quarantine(scope(PersistenceScopeType.COOP_AUTHORITY, "coop-a")));
        quarantines.openImmediate(quarantine(scope(PersistenceScopeType.BREEDING_PARENT, "parent-a")));

        assertEquals(PersistenceMutationAvailabilityStatus.QUARANTINED,
                service.decide(context(scope(PersistenceScopeType.COOP_AUTHORITY, "coop-a"))).status());
        assertTrue(service.decide(context(scope(PersistenceScopeType.COOP_AUTHORITY, "coop-b"))).allowed());
        assertEquals(PersistenceMutationAvailabilityStatus.QUARANTINED,
                service.decide(context(scope(PersistenceScopeType.BREEDING_PARENT, "parent-a"))).status());
        assertTrue(service.decide(context(scope(PersistenceScopeType.BREEDING_PARENT, "parent-c"))).allowed());
    }

    @Test
    void coverageCircuitAndGlobalStorageHaveDistinctReasons() {
        coverageReady = false;
        assertEquals(PersistenceMutationAvailabilityStatus.AUTHORITY_NOT_READY,
                service.decide(context(scope(PersistenceScopeType.PROFILE, "profile-a"))).status());

        coverageReady = true;
        circuits.publish(PersistenceDomain.MANAGED_COOP_RELEASE, false, "maintenance", 1L, "operator");
        assertEquals(PersistenceMutationAvailabilityStatus.FEATURE_PAUSED,
                service.decide(context(scope(PersistenceScopeType.PROFILE, "profile-a"))).status());

        storage.enterReadOnly("disk_full", "incident-storage");
        PersistenceMutationAvailabilityDecision decision = service.decide(
                context(scope(PersistenceScopeType.PROFILE, "profile-b")));
        assertEquals(PersistenceMutationAvailabilityStatus.GLOBAL_READ_ONLY, decision.status());
        assertEquals("incident-storage", decision.incidentId());
    }

    @Test
    void verifiedScopesAreImmediatelyUsableWithoutLoginDelay() {
        assertTrue(service.decide(context(scope(PersistenceScopeType.PROFILE, "profile-first-login"))).allowed());
    }

    private PersistenceMutationContext context(PersistenceScope scope) {
        return new PersistenceMutationContext(
                PersistenceDomain.MANAGED_COOP_RELEASE,
                "release",
                List.of(scope),
                Set.of("owner-global"),
                PersistenceMutationDelta.POSITIVE,
                "trace",
                "operation",
                true,
                true
        );
    }

    private PersistenceQuarantineRecord quarantine(PersistenceScope scope) {
        return new PersistenceQuarantineRecord(
                "q-" + scope.key(), "incident-" + scope.key(), scope,
                PersistenceDomain.MANAGED_COOP_RELEASE, "test_quarantine",
                PersistenceQuarantineState.ACTIVE, "evidence", 1L, 1L, 1L, 0L, null
        );
    }

    private PersistenceScope scope(PersistenceScopeType type, String key) {
        return new PersistenceScope(type, key, "hash-" + key, null);
    }
}
