package com.alechilles.alecstamework.npc.breeding;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Regression: a restart or projection UUID remap must address the same breeding attempt. */
class BreedingAttemptIdentityTest {
    @Test
    void canonicalProfilesAndPersistedCooldownGenerationSurviveOrderAndUuidChanges() {
        BreedingParentIdentity parentA = parent(1L, "profile-a");
        BreedingParentIdentity parentB = parent(2L, "profile-b");
        AppliedCooldownFingerprint cooldownA = fingerprint(101L, 201L, 100L, 501L);
        AppliedCooldownFingerprint cooldownB = fingerprint(102L, 202L, 100L, 501L);

        UUID initial = BreedingAttemptIdentity.forAppliedCooldowns(
                parentA, cooldownA, parentB, cooldownB
        );
        UUID replay = BreedingAttemptIdentity.forPersistedCooldowns(
                parent(99L, "profile-b"), snapshot(102L, 202L, 100L, 501L),
                parent(98L, "profile-a"), snapshot(101L, 201L, 100L, 501L)
        );

        assertEquals(initial, replay);
        assertEquals("breeding:" + initial, BreedingAttemptIdentity.attemptKey(initial));
    }

    @Test
    void nextCooldownGenerationCreatesANewAttempt() {
        BreedingParentIdentity parentA = parent(1L, "profile-a");
        BreedingParentIdentity parentB = parent(2L, "profile-b");

        UUID first = BreedingAttemptIdentity.forAppliedCooldowns(
                parentA, fingerprint(101L, 201L, 100L, 501L),
                parentB, fingerprint(102L, 202L, 100L, 501L)
        );
        UUID next = BreedingAttemptIdentity.forAppliedCooldowns(
                parentA, fingerprint(301L, 401L, 100L, 502L),
                parentB, fingerprint(302L, 402L, 100L, 502L)
        );

        assertNotEquals(first, next);
    }

    @Test
    void consecutiveZeroCooldownAttemptsUseDistinctPersistedJournalNonces() {
        BreedingParentIdentity parentA = parent(1L, "profile-a");
        BreedingParentIdentity parentB = parent(2L, "profile-b");

        UUID first = BreedingAttemptIdentity.forAppliedCooldowns(
                parentA, fingerprint(0L, 0L, 0L, 700L),
                parentB, fingerprint(0L, 0L, 0L, 700L),
                new UUID(0L, 10L)
        );
        UUID next = BreedingAttemptIdentity.forAppliedCooldowns(
                parentA, fingerprint(0L, 0L, 0L, 700L),
                parentB, fingerprint(0L, 0L, 0L, 700L),
                new UUID(0L, 11L)
        );

        assertNotEquals(first, next);
    }

    private static BreedingParentIdentity parent(long entityId, String profileId) {
        return new BreedingParentIdentity(new UUID(0L, entityId), profileId);
    }

    private static AppliedCooldownFingerprint fingerprint(long started,
                                                          long until,
                                                          long duration,
                                                          long generation) {
        return new AppliedCooldownFingerprint(
                true,
                false,
                until,
                started,
                duration,
                null,
                generation,
                null,
                0L,
                ParentBreedingSnapshot.AlarmSnapshot.missing()
        );
    }

    private static ParentBreedingSnapshot snapshot(long started,
                                                   long until,
                                                   long duration,
                                                   long generation) {
        return new ParentBreedingSnapshot(
                null,
                0.0,
                generation,
                false,
                true,
                until,
                started,
                duration,
                null,
                null,
                0L,
                ParentBreedingSnapshot.AlarmSnapshot.missing()
        );
    }
}
