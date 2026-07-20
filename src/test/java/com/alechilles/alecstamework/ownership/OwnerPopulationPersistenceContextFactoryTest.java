package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies owner-population operations retain their narrow feature and durable resource scopes. */
class OwnerPopulationPersistenceContextFactoryTest {
    private final OwnerPopulationPersistenceContextFactory factory =
            new OwnerPopulationPersistenceContextFactory(
                    new PersistenceScopeFactory(new byte[32]));

    @Test
    void storedSourceReleaseUsesCaptureReleaseCircuit() {
        var context = factory.create(plan(
                OwnerPopulationOperation.RESTORE,
                "spawner_release",
                "{\"idempotencyKey\":\"spawner-release:one\"}"
        ));

        assertEquals(PersistenceDomain.CAPTURE_RELEASE, context.domain());
        assertTrue(context.scopes().stream().anyMatch(scope ->
                scope.type() == PersistenceScopeType.OPERATION
                        && scope.key().equals("spawner-release:one")));
    }

    @Test
    void explicitCaptureIntakeOverridesGenericOwnerOperation() {
        var context = factory.create(plan(
                OwnerPopulationOperation.LIFECYCLE_CHANGE,
                "lifecycle_change",
                "{\"persistenceDomain\":\"CAPTURE_INTAKE\"}"
        ));

        assertEquals(PersistenceDomain.CAPTURE_INTAKE, context.domain());
    }

    @Test
    void breedingAddsAttemptParentsAndReplayCoverage() {
        var context = factory.create(plan(
                OwnerPopulationOperation.BREEDING,
                "breeding_retry",
                "{\"idempotencyKey\":\"breeding:attempt-1\","
                        + "\"parentProfileIds\":[\"parent-A\",\"parent-B\"]}"
        ));

        assertEquals(PersistenceDomain.BREEDING_BIRTH, context.domain());
        assertTrue(context.requiredCoverage().contains(
                PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL.key()));
        assertTrue(context.scopes().stream().anyMatch(scope ->
                scope.type() == PersistenceScopeType.BREEDING_ATTEMPT));
        assertEquals(2, context.scopes().stream().filter(scope ->
                scope.type() == PersistenceScopeType.BREEDING_PARENT).count());
    }

    @Test
    void managedCoopAddsAuthorityAndSlotScope() {
        String target = "{\"managedCoopMutation\":{"
                + "\"worldName\":\"default\",\"x\":1,\"y\":2,\"z\":3,"
                + "\"residentSlot\":4}}";
        var context = factory.create(plan(
                OwnerPopulationOperation.LIFECYCLE_CHANGE, "coop_capture", target));

        assertEquals(PersistenceDomain.MANAGED_COOP_INTAKE, context.domain());
        assertTrue(context.requiredCoverage().contains(
                PersistenceEvidenceDimension.MANAGED_COOP_CATALOG.key()));
        assertTrue(context.scopes().stream().anyMatch(scope ->
                scope.type() == PersistenceScopeType.COOP_AUTHORITY));
        assertTrue(context.scopes().stream().anyMatch(scope ->
                scope.type() == PersistenceScopeType.COOP_SLOT));
    }

    private OwnerPopulationAdmissionPlan plan(OwnerPopulationOperation operation,
                                              String source,
                                              String targetContext) {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000011");
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                "profile-1", -1L, null, null, owner, "default",
                CompanionLifecycleState.ACTIVE, operation,
                OwnerPopulationLimitScope.GLOBAL, 100, false
        );
        CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                "profile-1", null, null, "default", "default", "ACTIVE",
                null, null, null, 0L, source, 1L, 1L
        );
        return new OwnerPopulationAdmissionPlan(
                transition, baseline, null, null, null, null, source,
                "{}", "{}", targetContext, 1L, ClaimProviderGeneration.NONE
        );
    }
}
