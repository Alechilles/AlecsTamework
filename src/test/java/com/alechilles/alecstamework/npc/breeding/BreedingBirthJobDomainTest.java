package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for immutable breeding job identity, state, and reservation values. */
class BreedingBirthJobDomainTest {
    @Test
    void preservesFertilitySnapshotAndDefensivelyCopiesAdmissionsAndCounts() {
        PlannedChild cattle = child("cattle");
        PlannedChild sheep = child("sheep");
        ArrayList<PlannedChild> sourceChildren = new ArrayList<>(List.of(cattle, sheep));
        HashMap<String, Integer> sourceCounts = new HashMap<>(Map.of("cattle", 1, "sheep", 1));
        BreedingReservationScope scope = new BreedingReservationScope(12.0, null, List.of());
        BreedingBirthReservation reservation = new BreedingBirthReservation(scope, sourceCounts);
        BreedingJobAdmission admission = new BreedingJobAdmission(sourceChildren, reservation);
        BreedingFertilitySnapshot fertility = new BreedingFertilitySnapshot(1.4, 1.4, 1.96, 0.25, 2);
        BreedingBirthPlan plan = new BreedingBirthPlan(fertility, sourceChildren);

        sourceChildren.clear();
        sourceCounts.clear();

        assertEquals(fertility, plan.fertilitySnapshot());
        assertEquals(List.of(cattle, sheep), plan.children());
        assertEquals(Map.of("cattle", 1, "sheep", 1), reservation.countsByPopulationType());
        assertEquals(List.of(cattle, sheep), admission.children());
        assertThrows(UnsupportedOperationException.class, () -> admission.children().add(cattle));
        assertThrows(UnsupportedOperationException.class,
                () -> reservation.countsByPopulationType().put("goat", 1));
    }

    @Test
    void canonicalParentOrderKeepsSnapshotsAndFingerprintsAttachedToTheirParent() {
        BreedingParentIdentity parentA = new BreedingParentIdentity(uuid(20L), "profile-a");
        BreedingParentIdentity parentB = new BreedingParentIdentity(uuid(10L), "profile-b");
        ParentBreedingSnapshot snapshotA = snapshot("config-a", -500L, -900L);
        ParentBreedingSnapshot snapshotB = snapshot("config-b", -600L, -1_000L);
        AppliedCooldownFingerprint fingerprintA = fingerprint(parentB.entityUuid(), -300L);
        AppliedCooldownFingerprint fingerprintB = fingerprint(parentA.entityUuid(), -400L);
        BreedingBirthPlan plan = BreedingBirthPlan.of(List.of());

        BreedingBirthJob job = BreedingBirthJob.reserved(
                uuid(100L),
                "world",
                parentA,
                parentB,
                BreedingPopulationAdmissionService.BreedingMode.MANUAL,
                plan,
                BreedingJobAdmission.of(List.of(), BreedingReservationScope.unscoped()),
                snapshotA,
                snapshotB,
                fingerprintA,
                fingerprintB,
                new BreedingBirthAnchor(1.0, 2.0, 3.0)
        );

        assertEquals(parentB, job.firstParent());
        assertEquals(snapshotB, job.firstParentSnapshot());
        assertEquals(fingerprintB, job.firstParentCooldownFingerprint());
        assertEquals(parentA, job.secondParent());
        assertEquals(snapshotA, job.secondParentSnapshot());
        assertEquals(fingerprintA, job.secondParentCooldownFingerprint());
        assertEquals(-1_000L, job.firstParentSnapshot().cooldownUntilMs());
    }

    @Test
    void reservationScopesNormalizePlayerOrderAndRejectInvalidCapacityIdentity() {
        UUID ownerA = uuid(1L);
        UUID ownerB = uuid(2L);
        ArrayList<BreedingPlayerCapacityScope> source = new ArrayList<>(List.of(
                BreedingPlayerCapacityScope.global(ownerB),
                BreedingPlayerCapacityScope.perWorld("world", ownerA),
                BreedingPlayerCapacityScope.global(ownerB)
        ));

        BreedingReservationScope scope = new BreedingReservationScope(
                8.0,
                new BreedingClaimCapacityScope("provider", "world", "claim-1"),
                source
        );
        source.clear();

        assertEquals(List.of(
                BreedingPlayerCapacityScope.perWorld("world", ownerA),
                BreedingPlayerCapacityScope.global(ownerB)
        ), scope.playerScopes());
        assertThrows(UnsupportedOperationException.class,
                () -> scope.playerScopes().add(BreedingPlayerCapacityScope.global(ownerA)));
        assertThrows(IllegalArgumentException.class,
                () -> BreedingPlayerCapacityScope.perWorld(" ", ownerA));
        assertThrows(IllegalArgumentException.class,
                () -> new BreedingBirthAnchor(Double.NaN, 0.0, 0.0));
    }

    private static ParentBreedingSnapshot snapshot(String configId, long startedAtMs, long untilMs) {
        return new ParentBreedingSnapshot(
                configId,
                75.0,
                123L,
                true,
                true,
                untilMs,
                startedAtMs,
                400L,
                uuid(50L),
                uuid(60L),
                1_000L,
                ParentBreedingSnapshot.AlarmSnapshot.set(untilMs)
        );
    }

    private static AppliedCooldownFingerprint fingerprint(UUID partnerUuid, long untilMs) {
        return new AppliedCooldownFingerprint(
                true,
                false,
                untilMs,
                untilMs - 100L,
                100L,
                partnerUuid,
                456L,
                null,
                0L,
                ParentBreedingSnapshot.AlarmSnapshot.set(untilMs)
        );
    }

    private static PlannedChild child(String type) {
        return new PlannedChild("baby_" + type, "adult_" + type, "Female", "family_" + type, type);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
