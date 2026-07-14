package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for exact-admission partial-litter crash/restart replay. */
class BreedingPopulationReplayServiceTest {
    private static final String ATTEMPT = "breeding:stable-job";
    private static final String SECOND_ATTEMPT = "breeding:second-job";
    private static final String WORLD = "test-world";
    private static final List<String> PARENTS = List.of("profile-parent-a", "profile-parent-z");

    @Test
    void unsealedProjectionScanStillAllowsFreshCurrentSessionAttempt() {
        BreedingPopulationReplayService replay = unsealedReplay(List.of());
        BreedingPopulationAdmissionRequest request = request(
                plan(), 1, Set.of("child-0")
        );

        assertTrue(replay.stateForPair(WORLD, PARENTS).usable());
        assertTrue(replay.accepts(request));
        assertTrue(replay.recordPrepared(request, List.of(reserved("child-0"))));
        assertTrue(replay.currentForSpawn(ATTEMPT, "child-0"));
    }

    @Test
    void unsealedProjectionScanBlocksOnlyThePersistedPair() {
        BreedingPopulationReplayService replay = unsealedReplay(List.of(operation(
                ATTEMPT,
                "child-0",
                CompanionPopulationOperationRecord.State.PREPARED,
                plan(),
                true,
                true,
                WORLD
        )));

        BreedingPopulationReplayState persisted = replay.stateForPair(WORLD, PARENTS);
        BreedingPopulationReplayState unrelated = replay.stateForPair(
                WORLD, List.of("profile-other-a", "profile-other-b")
        );

        assertFalse(persisted.usable());
        assertEquals(
                "breeding-replay-projection-evidence-unavailable", persisted.reason()
        );
        assertTrue(unrelated.usable());
        assertEquals("breeding-replay-pair-empty", unrelated.reason());
    }

    @Test
    void journalRefreshCannotDiscardCurrentAttemptMissingFromLoadedSnapshot() {
        BreedingPopulationReplayService replay = unsealedReplay(List.of());
        BreedingPopulationAdmissionRequest request = request(
                plan(), 1, Set.of("child-0")
        );
        assertTrue(replay.recordPrepared(request, List.of(reserved("child-0"))));

        replay.replace(List.of());

        assertTrue(replay.currentForSpawn(ATTEMPT, "child-0"));
        assertEquals(
                Set.of("child-0"),
                replay.stateForPair(WORLD, PARENTS).pendingChildKeys()
        );
    }

    @Test
    void partialCapAdmittedSubsetNeverResurrectsUnadmittedPlanChildren() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayService replay = new BreedingPopulationReplayService(List.of());
        BreedingPopulationAdmissionRequest request = request(plan, 1);
        PreparedBreedingPopulationBatch.ReservedChild admitted = reserved("child-0");

        assertTrue(replay.recordPrepared(request, List.of(admitted)));
        BreedingPopulationReplayState beforeCommit = replay.stateForPair(WORLD, PARENTS);
        assertEquals(Set.of("child-0"), beforeCommit.pendingChildKeys());
        assertEquals(ATTEMPT, beforeCommit.attemptKey());
        assertTrue(replay.accepts(request(plan, 1, Set.of("child-0"))));
        assertFalse(replay.accepts(request(plan, 4)));

        replay.recordCommitted(
                ATTEMPT,
                admitted.childKey(),
                admitted.profileId(),
                admitted.plannedNpcUuid(),
                plan
        );

        BreedingPopulationReplayState pairState = replay.stateForPair(WORLD, PARENTS);
        BreedingPopulationReplayState exactState = replay.state(ATTEMPT);
        assertTrue(pairState.usable());
        assertNull(pairState.attemptKey());
        assertTrue(pairState.pendingChildKeys().isEmpty());
        assertEquals(Set.of("child-0"), exactState.committedChildKeys());
        assertTrue(exactState.pendingChildKeys().isEmpty());
    }

    @Test
    void canceledAttemptIsNotPairReplayableButAmbiguousCancellationStaysPending() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayService replay = new BreedingPopulationReplayService(List.of());
        PreparedBreedingPopulationBatch.ReservedChild admitted = reserved("child-0");
        replay.recordPrepared(request(plan, 1), List.of(admitted));

        assertEquals(
                Set.of("child-0"),
                replay.stateForPair(WORLD, PARENTS).pendingChildKeys()
        );

        replay.recordAborted(ATTEMPT, "child-0", plan);

        assertTrue(replay.stateForPair(WORLD, PARENTS).pendingChildKeys().isEmpty());
        assertTrue(replay.state(ATTEMPT).pendingChildKeys().isEmpty());
    }

    @Test
    void partialCommitReturnsOnlyJournalProvenPendingChild() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.COMMITTED, plan, true, true, WORLD
                ),
                operation(
                        ATTEMPT, "child-1",
                        CompanionPopulationOperationRecord.State.APPLYING, plan, true, true, WORLD
                )
        )).stateForPair(WORLD, List.of("profile-parent-z", "profile-parent-a"));

        assertTrue(replay.usable());
        assertEquals(ATTEMPT, replay.attemptKey());
        assertEquals(plan, replay.birthPlan());
        assertEquals(Set.of("child-1"), replay.pendingChildKeys());
        assertEquals(Set.of("child-0"), replay.committedChildKeys());
    }

    @Test
    void retryableRecoveryRowRemainsPendingForExactReplay() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayService service = new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-1",
                        CompanionPopulationOperationRecord.State.RETRYABLE,
                        plan, true, true, WORLD
                )
        ));

        BreedingPopulationReplayState exact = service.state(ATTEMPT);
        BreedingPopulationReplayState pair = service.stateForPair(WORLD, PARENTS);

        assertTrue(exact.usable());
        assertEquals(Set.of("child-1"), exact.pendingChildKeys());
        assertEquals(ATTEMPT, pair.attemptKey());
        assertEquals(Set.of("child-1"), pair.pendingChildKeys());
    }

    @Test
    void twoPendingAttemptsForCanonicalPairFailClosed() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.PREPARED, plan, true, true, WORLD
                ),
                operation(
                        SECOND_ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.APPLYING, plan, true, true, WORLD
                )
        )).stateForPair(WORLD, PARENTS);

        assertFalse(replay.usable());
        assertNull(replay.attemptKey());
        assertEquals("breeding-replay-pair-conflict", replay.reason());
    }

    @Test
    void parentInPendingAttemptCannotBeQueriedWithDifferentPartner() {
        BreedingPopulationReplayService service = new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.PREPARED,
                        plan(), true, true, WORLD
                )
        ));

        BreedingPopulationReplayState replay = service.stateForPair(
                WORLD, List.of("profile-parent-a", "profile-parent-c")
        );

        assertFalse(replay.usable());
        assertNull(replay.attemptKey());
        assertEquals("breeding-replay-parent-conflict", replay.reason());
    }

    @Test
    void pairReplayRejectsWorldChange() {
        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.PREPARED,
                        plan(), true, true, WORLD
                )
        )).stateForPair("different-world", PARENTS);

        assertFalse(replay.usable());
        assertEquals(ATTEMPT, replay.attemptKey());
        assertEquals("breeding-replay-world-mismatch", replay.reason());
    }

    @Test
    void repeatedServiceReconstructionSelectsSameExactAttempt() {
        BreedingBirthPlanSnapshot plan = plan();
        List<CompanionPopulationOperationRecord> operations = List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.COMMITTED, plan, true, true, WORLD
                ),
                operation(
                        ATTEMPT, "child-1",
                        CompanionPopulationOperationRecord.State.APPLIED, plan, true, true, WORLD
                )
        );

        BreedingPopulationReplayState first = new BreedingPopulationReplayService(
                operations
        ).stateForPair(WORLD, PARENTS);
        BreedingPopulationReplayState second = new BreedingPopulationReplayService(
                operations
        ).stateForPair(WORLD, PARENTS);

        assertEquals(first, second);
        assertEquals(ATTEMPT, first.attemptKey());
        assertEquals(Set.of("child-1"), first.pendingChildKeys());
    }

    @Test
    void failedAndCommittedRowsNeverBecomePending() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationReplayState exact = new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.FAILED, plan, true, true, WORLD
                ),
                operation(
                        ATTEMPT, "child-1",
                        CompanionPopulationOperationRecord.State.COMMITTED, plan, true, true, WORLD
                )
        )).state(ATTEMPT);

        assertTrue(exact.usable());
        assertTrue(exact.pendingChildKeys().isEmpty());
        assertEquals(Set.of("child-1"), exact.committedChildKeys());
        assertTrue(new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.FAILED, plan, true, true, WORLD
                )
        )).stateForPair(WORLD, PARENTS).pendingChildKeys().isEmpty());
    }

    @Test
    void legacyTargetSupportsExactLookupButPairLookupDoesNotGuess() {
        CompanionPopulationOperationRecord legacy = operation(
                ATTEMPT,
                "child-0",
                CompanionPopulationOperationRecord.State.PREPARED,
                plan(),
                true,
                false,
                WORLD
        );
        BreedingPopulationReplayService service = new BreedingPopulationReplayService(
                List.of(legacy)
        );

        BreedingPopulationReplayState exact = service.state(ATTEMPT);
        BreedingPopulationReplayState pair = service.stateForPair(WORLD, PARENTS);

        assertTrue(exact.usable());
        assertEquals(Set.of("child-0"), exact.pendingChildKeys());
        assertFalse(pair.usable());
        assertEquals("breeding-replay-pair-metadata-missing", pair.reason());
    }

    @Test
    void identityMismatchConflictsOnlyTheDerivableAttempt() {
        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(List.of(
                operation(
                        ATTEMPT, "child-0",
                        CompanionPopulationOperationRecord.State.COMMITTED,
                        plan(), false, true, WORLD
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
    void committedLegacyEvidenceWithoutPersistedPlanFailsClosed() {
        CompanionPopulationOperationRecord committed = operation(
                ATTEMPT,
                "child-0",
                CompanionPopulationOperationRecord.State.COMMITTED,
                null,
                true,
                false,
                WORLD
        );

        BreedingPopulationReplayState replay = new BreedingPopulationReplayService(
                List.of(committed)
        ).state(ATTEMPT);

        assertFalse(replay.usable());
        assertEquals("breeding-replay-journal-unavailable", replay.reason());
    }

    @Test
    void snapshotJsonRoundTripsWithoutChangingDeterministicChildData() {
        BreedingBirthPlanSnapshot plan = plan();

        BreedingBirthPlanSnapshot decoded = BreedingBirthPlanSnapshotJsonCodec.decode(
                BreedingBirthPlanSnapshotJsonCodec.encode(plan)
        );

        assertEquals(plan, decoded);
    }

    private static BreedingPopulationAdmissionRequest request(
            BreedingBirthPlanSnapshot plan,
            int maximumAdmitted
    ) {
        return request(
                plan,
                maximumAdmitted,
                plan.children().stream().map(
                        BreedingBirthPlanSnapshot.PlannedChild::childKey
                ).collect(java.util.stream.Collectors.toSet())
        );
    }

    private static BreedingPopulationReplayService unsealedReplay(
            List<CompanionPopulationOperationRecord> operations
    ) {
        return new BreedingPopulationReplayService(
                operations,
                true,
                new BreedingPersistedProjectionReplayGuard(
                        new CompanionPersistedProjectionEvidenceRegistry()
                )
        );
    }

    private static BreedingPopulationAdmissionRequest request(
            BreedingBirthPlanSnapshot plan,
            int maximumAdmitted,
            Set<String> requestedChildKeys
    ) {
        List<BreedingPopulationAdmissionRequest.PlannedChild> children = plan.children().stream()
                .filter(child -> requestedChildKeys.contains(child.childKey()))
                .map(child -> new BreedingPopulationAdmissionRequest.PlannedChild(
                        child.childKey(), child.ownerId(), child.ownerName()
                ))
                .toList();
        return new BreedingPopulationAdmissionRequest(
                WORLD,
                2,
                3,
                children,
                maximumAdmitted,
                false,
                ATTEMPT,
                plan,
                List.of("profile-parent-z", "profile-parent-a", "profile-parent-a")
        );
    }

    private static PreparedBreedingPopulationBatch.ReservedChild reserved(String childKey) {
        return new PreparedBreedingPopulationBatch.ReservedChild(
                childKey,
                BreedingAdmissionIdentity.profileId(ATTEMPT, childKey),
                BreedingAdmissionIdentity.npcUuid(ATTEMPT, childKey),
                null,
                null
        );
    }

    private static CompanionPopulationOperationRecord operation(
            String attemptKey,
            String childKey,
            CompanionPopulationOperationRecord.State state,
            BreedingBirthPlanSnapshot plan,
            boolean matchingIdentity,
            boolean includePair,
            String world
    ) {
        String profileId = matchingIdentity
                ? BreedingAdmissionIdentity.profileId(attemptKey, childKey)
                : UUID.randomUUID().toString();
        UUID npcUuid = BreedingAdmissionIdentity.npcUuid(attemptKey, childKey);
        JsonObject target = new JsonObject();
        target.addProperty("idempotencyKey", attemptKey);
        target.addProperty("childKey", childKey);
        target.addProperty("plannedNpcUuid", npcUuid.toString());
        target.addProperty("world", world);
        if (includePair) {
            JsonArray parents = new JsonArray();
            parents.add("profile-parent-z");
            parents.add("profile-parent-a");
            target.add("parentProfileIds", parents);
        }
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
                4,
                List.of(
                        child("child-0", "role-baby-a", 11),
                        child("child-1", "role-baby-b", 12),
                        child("child-2", "role-baby-c", 13),
                        child("child-3", "role-baby-d", 14)
                )
        );
    }

    private static BreedingBirthPlanSnapshot.PlannedChild child(
            String childKey,
            String roleId,
            int roleIndex
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
