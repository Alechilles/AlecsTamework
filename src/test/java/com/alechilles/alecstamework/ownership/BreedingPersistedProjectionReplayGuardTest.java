package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.LoadedNpcObservation;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionKey;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionLiveEvidenceRevision;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionEvidence;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for terminal RETRYABLE children surviving in persisted world chunks. */
class BreedingPersistedProjectionReplayGuardTest {
    private static final String ATTEMPT = "breeding:restart-attempt";
    private static final String CHILD = "child-0";
    private static final String PROFILE = BreedingAdmissionIdentity.profileId(ATTEMPT, CHILD);
    private static final UUID PLANNED = BreedingAdmissionIdentity.npcUuid(ATTEMPT, CHILD);
    private static final UUID ALTERNATE = new UUID(44L, 55L);
    private static final List<String> PARENTS = List.of("parent-a", "parent-b");

    @Test
    void exactPersistedChildWithOrdinaryPhysicalEvidenceConvergesAsCommitted() {
        CompanionPersistedProjectionEvidenceRegistry registry = registry(List.of(
                marker("exact", PLANNED, PLANNED, false, "world", 4, 5),
                physical("exact-physical", "world", 4, 5)));
        BreedingPopulationReplayService replay = replay(registry);

        BreedingPopulationReplayState state = replay.state(ATTEMPT);

        assertTrue(state.usable());
        assertTrue(state.pendingChildKeys().isEmpty());
        assertEquals(java.util.Set.of(CHILD), state.committedChildKeys());
    }

    @Test
    void exactPersistedAndUniqueLoadedChildConvergeAsTheSameCommittedProjection() {
        Fixture fixture = fixture(
                List.of(
                        marker("exact", PLANNED, PLANNED, false, "world", 4, 5),
                        physical("exact-physical", "world", 4, 5)),
                List.of(loaded(PLANNED, PLANNED, "loaded-store")));

        BreedingPopulationReplayState state = replay(fixture.registry()).state(ATTEMPT);

        assertTrue(state.usable());
        assertTrue(state.pendingChildKeys().isEmpty());
        assertEquals(java.util.Set.of(CHILD), state.committedChildKeys());
    }

    @Test
    void loadedMarkerWithoutMatchingPersistedEvidenceBlocksRetryableReplay() {
        Fixture fixture = fixture(
                List.of(), List.of(loaded(PLANNED, PLANNED, "loaded-store")));

        BreedingPopulationReplayState state = replay(fixture.registry()).state(ATTEMPT);

        assertFalse(state.usable());
        assertEquals("breeding-replay-loaded-projection-observed", state.reason());
    }

    /** Regression: markerless saved state for the deterministic child is presence, not absence. */
    @Test
    void markerlessOrdinaryEvidenceAndConflictsNeverAuthorizeRetry() {
        String reason = "breeding-replay-ordinary-evidence-without-projection-marker";
        assertBlocked(List.of(physical("markerless-physical", "world", 4, 5)), reason);
        for (CompanionPopulationEvidence.Kind kind : List.of(
                CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT,
                CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT,
                CompanionPopulationEvidence.Kind.LOST_SNAPSHOT,
                CompanionPopulationEvidence.Kind.COOP_SNAPSHOT,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                CompanionPopulationEvidence.Kind.PROFILE_RECORD)) {
            assertBlocked(List.of(dormant("markerless-" + kind.name(), kind)), reason);
        }
        assertBlocked(List.of(
                physical("conflict-a", "world", 4, 5),
                physical("conflict-b", "world", 8, 9)), reason);
    }

    @Test
    void markerAddAndUnloadAfterJournalLoadInvalidatesThePendingReplayToken() {
        Fixture fixture = fixture(List.of(), List.of());
        BreedingPopulationReplayService replay = replay(fixture.registry());
        assertTrue(replay.state(ATTEMPT).usable());
        assertTrue(replay.currentForSpawn(ATTEMPT, CHILD));
        LoadedNpcObservation observation = loaded(PLANNED, PLANNED, "loaded-store");

        fixture.loadedIndex().recordAdded(observation);
        fixture.loadedIndex().recordRemoved(observation);

        BreedingPopulationReplayState state = replay.state(ATTEMPT);
        assertFalse(state.usable());
        assertFalse(replay.currentForSpawn(ATTEMPT, CHILD));
        assertEquals("breeding-replay-projection-evidence-changed", state.reason());
    }

    @Test
    void exactMarkerWithoutOrdinaryPhysicalEvidenceRemainsBlocked() {
        BreedingPopulationReplayState state = replay(registry(List.of(
                marker("exact-only", PLANNED, PLANNED, false, "world", 4, 5))))
                .state(ATTEMPT);

        assertFalse(state.usable());
        assertEquals("breeding-replay-persisted-projection-ordinary-conflict", state.reason());
    }

    @Test
    void alternateDuplicateAndDeadPersistedChildrenAllFailClosed() {
        assertBlocked(
                List.of(marker("alternate", ALTERNATE, ALTERNATE, false, "world", 4, 5)),
                "breeding-replay-persisted-projection-identity-mismatch");
        assertBlocked(
                List.of(
                        marker("duplicate-a", PLANNED, PLANNED, false, "world", 4, 5),
                        marker("duplicate-b", PLANNED, PLANNED, false, "world", 4, 5)),
                "breeding-replay-persisted-projection-duplicated");
        assertBlocked(
                List.of(marker("dead", PLANNED, PLANNED, true, "world", 4, 5)),
                "breeding-replay-persisted-projection-dead");
    }

    @Test
    void missingLegacyOwnerAndLocationMismatchesFailClosed() {
        assertBlocked(
                List.of(marker("missing-legacy", PLANNED, null, false, "world", 4, 5)),
                "breeding-replay-persisted-projection-identity-mismatch");
        assertBlocked(
                List.of(marker("wrong-location", PLANNED, PLANNED, false, "world", 9, 5)),
                "breeding-replay-persisted-projection-location-mismatch");
        assertBlocked(
                List.of(new CompanionPopulationEvidence(
                        evidenceKey("wrong-owner", PLANNED, PLANNED, false),
                        PLANNED,
                        UUID.randomUUID(),
                        true,
                        CompanionPopulationEvidence.Kind.PROJECTION_MARKER,
                        "world", "world", 4, 5, "saved-world")),
                "breeding-replay-persisted-projection-owner-mismatch");
    }

    @Test
    void sealedAbsenceAllowsExactRetryWhileUnsealedEvidenceDoesNot() {
        CompanionPersistedProjectionEvidenceRegistry empty = registry(List.of());
        BreedingPopulationReplayState retry = replay(empty).state(ATTEMPT);
        BreedingPopulationReplayState unsealed = replay(
                new CompanionPersistedProjectionEvidenceRegistry()).state(ATTEMPT);

        assertTrue(retry.usable());
        assertEquals(java.util.Set.of(CHILD), retry.pendingChildKeys());
        assertFalse(unsealed.usable());
        assertEquals(
                "breeding-replay-projection-evidence-unavailable", unsealed.reason()
        );
    }

    /** Regression: an APPLYING row must not hold its parents after its exact child is loaded. */
    @Test
    void exactUniqueLoadedChildClosesApplyingAttemptWhilePersistedScanIsDegraded() {
        Fixture fixture = degradedFixture(List.of(loaded(PLANNED, PLANNED, "loaded-store")));
        BreedingPopulationReplayService replay = replay(
                fixture.registry(), CompanionPopulationOperationRecord.State.APPLYING);

        BreedingPopulationReplayState pair = replay.stateForPair("world", PARENTS);
        BreedingPopulationReplayState attempt = replay.state(ATTEMPT);

        assertTrue(pair.usable());
        assertEquals("breeding-replay-pair-empty", pair.reason());
        assertTrue(attempt.usable());
        assertTrue(attempt.pendingChildKeys().isEmpty());
        assertEquals(java.util.Set.of(CHILD), attempt.committedChildKeys());
    }

    @Test
    void absentMismatchedAndDuplicateLoadedChildrenStayBlockedDuringDegradedScan() {
        assertDegradedLoadedEvidenceBlocked(List.of());
        assertDegradedLoadedEvidenceBlocked(List.of(
                loaded(ALTERNATE, ALTERNATE, "alternate-store")));
        assertDegradedLoadedEvidenceBlocked(List.of(
                loaded(PLANNED, PLANNED, "duplicate-a"),
                loaded(PLANNED, PLANNED, "duplicate-b")));
    }

    private static void assertDegradedLoadedEvidenceBlocked(
            List<LoadedNpcObservation> observations) {
        BreedingPopulationReplayState state = replay(
                degradedFixture(observations).registry(),
                CompanionPopulationOperationRecord.State.APPLYING).state(ATTEMPT);
        assertFalse(state.usable());
        assertEquals(java.util.Set.of(CHILD), state.pendingChildKeys());
        assertEquals("breeding-replay-projection-evidence-unavailable", state.reason());
    }

    private static void assertBlocked(
            List<CompanionPopulationEvidence> evidence,
            String expectedReason) {
        BreedingPopulationReplayState state = replay(registry(evidence)).state(ATTEMPT);
        assertFalse(state.usable());
        assertEquals(expectedReason, state.reason());
    }

    private static BreedingPopulationReplayService replay(
            CompanionPersistedProjectionEvidenceRegistry registry) {
        return replay(registry, CompanionPopulationOperationRecord.State.RETRYABLE);
    }

    private static BreedingPopulationReplayService replay(
            CompanionPersistedProjectionEvidenceRegistry registry,
            CompanionPopulationOperationRecord.State state) {
        return new BreedingPopulationReplayService(
                List.of(operation(state)),
                true,
                new BreedingPersistedProjectionReplayGuard(registry));
    }

    private static CompanionPersistedProjectionEvidenceRegistry registry(
            List<CompanionPopulationEvidence> evidence) {
        return fixture(evidence, List.of()).registry();
    }

    private static Fixture fixture(
            List<CompanionPopulationEvidence> evidence,
            List<LoadedNpcObservation> loadedObservations) {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        LoadedNpcIdentityIndex loadedIndex = new LoadedNpcIdentityIndex();
        for (LoadedNpcObservation observation : loadedObservations) {
            loadedIndex.recordAdded(observation);
        }
        loadedIndex.markInitializationComplete();
        CompanionLiveEvidenceRevision liveEvidence = new CompanionLiveEvidenceRevision();
        registry.bindLoadedIdentityIndex(loadedIndex);
        registry.bindLiveEvidenceRevision(liveEvidence);
        registry.begin("scan-a");
        assertTrue(registry.publishSealed(
                "scan-a", new CompanionPopulationEvidenceSet(evidence),
                loadedIndex.snapshot().mutationRevision(), liveEvidence.capture()));
        return new Fixture(registry, loadedIndex);
    }

    private static Fixture degradedFixture(List<LoadedNpcObservation> loadedObservations) {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        LoadedNpcIdentityIndex loadedIndex = new LoadedNpcIdentityIndex();
        for (LoadedNpcObservation observation : loadedObservations) {
            loadedIndex.recordAdded(observation);
        }
        loadedIndex.markInitializationComplete();
        registry.bindLoadedIdentityIndex(loadedIndex);
        registry.bindLiveEvidenceRevision(new CompanionLiveEvidenceRevision());
        registry.begin("scan-a");
        assertTrue(registry.degrade("scan-a", "saved-world-source-failed"));
        return new Fixture(registry, loadedIndex);
    }

    private static LoadedNpcObservation loaded(
            UUID componentUuid, UUID legacyUuid, String storeIdentity) {
        return new LoadedNpcObservation(
                componentUuid,
                legacyUuid,
                new Location("world", storeIdentity),
                new ProjectionKey(
                        PROFILE,
                        ATTEMPT,
                        TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                        CHILD,
                        PLANNED,
                        1L));
    }

    private static CompanionPopulationEvidence marker(
            String key,
            UUID component,
            UUID legacy,
            boolean dead,
            String world,
            int chunkX,
            int chunkZ) {
        UUID evidenceUuid = component != null ? component : legacy != null ? legacy : PLANNED;
        return new CompanionPopulationEvidence(
                evidenceKey(key, component, legacy, dead),
                evidenceUuid,
                null,
                true,
                CompanionPopulationEvidence.Kind.PROJECTION_MARKER,
                world,
                world,
                chunkX,
                chunkZ,
                "saved-world");
    }

    private static CompanionPopulationEvidence physical(
            String key,
            String world,
            int chunkX,
            int chunkZ) {
        return new CompanionPopulationEvidence(
                key,
                PLANNED,
                null,
                true,
                CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                world,
                world,
                chunkX,
                chunkZ,
                "saved-world");
    }

    private static CompanionPopulationEvidence dormant(
            String key,
            CompanionPopulationEvidence.Kind kind) {
        return new CompanionPopulationEvidence(
                key,
                PLANNED,
                null,
                kind != CompanionPopulationEvidence.Kind.PROFILE_RECORD,
                kind,
                "world",
                null,
                null,
                null,
                "saved-world");
    }

    private static String evidenceKey(
            String key,
            UUID component,
            UUID legacy,
            boolean dead) {
        String fingerprint = CompanionProjectionEvidence.fingerprint(
                PROFILE,
                ATTEMPT,
                TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                CHILD,
                PLANNED,
                1L);
        return CompanionProjectionEvidence.appendToEvidenceKey(
                key, fingerprint, component, legacy, dead);
    }

    private static CompanionPopulationOperationRecord operation(
            CompanionPopulationOperationRecord.State state) {
        JsonObject target = new JsonObject();
        target.addProperty("idempotencyKey", ATTEMPT);
        target.addProperty("childKey", CHILD);
        target.addProperty("plannedNpcUuid", PLANNED.toString());
        target.addProperty("world", "world");
        target.addProperty("chunkX", 4);
        target.addProperty("chunkZ", 5);
        JsonArray parents = new JsonArray();
        for (String parent : PARENTS) {
            parents.add(parent);
        }
        target.add("parentProfileIds", parents);
        BreedingBirthPlanSnapshot plan = new BreedingBirthPlanSnapshot(
                1.0,
                1.0,
                1.0,
                1,
                List.of(new BreedingBirthPlanSnapshot.PlannedChild(
                        CHILD,
                        "role-child",
                        1,
                        "role-adult",
                        "FEMALE",
                        false,
                        null,
                        null,
                        null,
                        null,
                        "global")));
        target.add("birthPlan", BreedingBirthPlanSnapshotJsonCodec.encode(plan));
        return new CompanionPopulationOperationRecord(
                "operation-row",
                PROFILE,
                OwnerPopulationOperation.BREEDING.name(),
                state,
                0L,
                "{\"ownerUuid\":null}",
                "{\"ownerUuid\":null}",
                target.toString(),
                10L,
                20L,
                20L,
                "retryable");
    }

    private record Fixture(
            CompanionPersistedProjectionEvidenceRegistry registry,
            LoadedNpcIdentityIndex loadedIndex) {
    }
}
