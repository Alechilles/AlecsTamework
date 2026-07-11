package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for partial-litter crash/restart journal replay. */
class BreedingPopulationReplayServiceTest {
    private static final String ATTEMPT = "breeding:stable-job";

    @Test
    void restartRestoresPlanButCountsOnlyIdentityMatchingCommittedOperations() {
        BreedingBirthPlanSnapshot plan = plan();
        CompanionPopulationOperationRecord committed = operation(
                "child-0", CompanionPopulationOperationRecord.State.COMMITTED, plan, true
        );
        CompanionPopulationOperationRecord failed = operation(
                "child-1", CompanionPopulationOperationRecord.State.FAILED, plan, true
        );
        BreedingPopulationReplayState beforeRestart = new BreedingPopulationReplayService(
                List.of(committed, failed)
        ).state(ATTEMPT);
        BreedingPopulationReplayState afterRestart = new BreedingPopulationReplayService(
                List.of(committed, failed)
        ).state(ATTEMPT);

        assertTrue(beforeRestart.usable());
        assertEquals(plan, beforeRestart.birthPlan());
        assertEquals(java.util.Set.of("child-0"), beforeRestart.committedChildKeys());
        assertEquals(beforeRestart, afterRestart);
    }

    @Test
    void identityMismatchConflictsTheDerivableAttempt() {
        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(List.of(
                operation(
                        "child-0", CompanionPopulationOperationRecord.State.COMMITTED,
                        plan(), false
                )
        )).state(ATTEMPT);

        assertFalse(replay.usable());
        assertEquals("breeding-replay-plan-conflict", replay.reason());
    }

    @Test
    void malformedRetainedBreedingEvidenceMakesReplayGloballyUnavailable() {
        long now = 100L;
        CompanionPopulationOperationRecord malformed = new CompanionPopulationOperationRecord(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                OwnerPopulationOperation.BREEDING.name(),
                CompanionPopulationOperationRecord.State.COMMITTED,
                0L, "{}", "{}", "{not-json", now, now, now, null
        );

        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(
                List.of(malformed)
        ).state("breeding:any-job");

        assertFalse(replay.usable());
        assertEquals("breeding-replay-journal-unavailable", replay.reason());
    }

    @Test
    void failedJournalAndPreparedBaselineNeverImplyBirth() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(List.of(
                operation("child-0", CompanionPopulationOperationRecord.State.FAILED, plan, true),
                operation("child-1", CompanionPopulationOperationRecord.State.PREPARED, plan, true)
        )).state(ATTEMPT);

        assertTrue(replay.usable());
        assertEquals(plan, replay.birthPlan());
        assertTrue(replay.committedChildKeys().isEmpty());
    }

    @Test
    void postRecoveryRefreshPromotesApplyingEvidenceOnlyAfterJournalCommit() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayService replayService = new BreedingPopulationReplayService(List.of(
                operation("child-0", CompanionPopulationOperationRecord.State.APPLYING, plan, true)
        ));
        assertTrue(replayService.state(ATTEMPT).committedChildKeys().isEmpty());

        replayService.replace(List.of(
                operation("child-0", CompanionPopulationOperationRecord.State.COMMITTED, plan, true)
        ));

        assertEquals(
                java.util.Set.of("child-0"),
                replayService.state(ATTEMPT).committedChildKeys()
        );
    }

    @Test
    void committedLegacyEvidenceWithoutPersistedPlanFailsClosed() {
        CompanionPopulationOperationRecord committed = operation(
                "child-0", CompanionPopulationOperationRecord.State.COMMITTED, null, true
        );

        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(
                List.of(committed)
        ).state(ATTEMPT);

        assertFalse(replay.usable());
        assertEquals("breeding-replay-journal-unavailable", replay.reason());
        assertTrue(replay.committedChildKeys().isEmpty());
    }

    @Test
    void snapshotJsonRoundTripsWithoutChangingDeterministicChildData() {
        BreedingBirthPlanSnapshot plan = plan();

        BreedingBirthPlanSnapshot decoded = BreedingBirthPlanSnapshotJsonCodec.decode(
                BreedingBirthPlanSnapshotJsonCodec.encode(plan)
        );

        assertEquals(plan, decoded);
    }

    private static CompanionPopulationOperationRecord operation(
            String childKey,
            CompanionPopulationOperationRecord.State state,
            BreedingBirthPlanSnapshot plan,
            boolean matchingIdentity
    ) {
        String profileId = matchingIdentity
                ? BreedingAdmissionIdentity.profileId(ATTEMPT, childKey)
                : UUID.randomUUID().toString();
        UUID npcUuid = BreedingAdmissionIdentity.npcUuid(ATTEMPT, childKey);
        JsonObject target = new JsonObject();
        target.addProperty("idempotencyKey", ATTEMPT);
        target.addProperty("childKey", childKey);
        target.addProperty("plannedNpcUuid", npcUuid.toString());
        if (plan != null) {
            target.add("birthPlan", BreedingBirthPlanSnapshotJsonCodec.encode(plan));
        }
        long now = 100L;
        return new CompanionPopulationOperationRecord(
                UUID.randomUUID().toString(),
                profileId,
                OwnerPopulationOperation.BREEDING.name(),
                state,
                0L,
                "{}",
                "{}",
                target.toString(),
                now,
                now,
                state.isTerminal() ? now : 0L,
                null
        );
    }

    private static BreedingBirthPlanSnapshot plan() {
        return new BreedingBirthPlanSnapshot(
                1.0,
                1.5,
                1.5,
                2,
                List.of(
                        child("child-0", "role-baby-a", 11),
                        child("child-1", "role-baby-b", 12)
                )
        );
    }

    private static BreedingBirthPlanSnapshot.PlannedChild child(
            String childKey, String roleId, int roleIndex
    ) {
        return new BreedingBirthPlanSnapshot.PlannedChild(
                childKey,
                roleId,
                roleIndex,
                "role-adult",
                "FEMALE",
                false,
                null,
                null,
                UUID.nameUUIDFromBytes((childKey + "-owner").getBytes()),
                childKey + " owner",
                "family-type"
        );
    }
}
