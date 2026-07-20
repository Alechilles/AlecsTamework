package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationEvidenceSetTest {
    @Test
    void physicalEvidenceWinsOverCopiedCapturedItemAtTheSameIdentity() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                physical("physical", npcUuid, ownerUuid, "default", 4, -2),
                captured("copy", npcUuid, ownerUuid)
        ));

        assertTrue(set.isConflictFree());
        CompanionPopulationEvidenceSet.ResolvedEvidence resolved = set.evidence().getFirst();
        assertTrue(resolved.physical());
        assertEquals("default", resolved.physicalLocation().worldName());
        assertEquals(2, resolved.observationCount());
    }

    @Test
    void conflictingCopiedOwnersQuarantineTheIdentity() {
        UUID npcUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                captured("a", npcUuid, UUID.randomUUID()),
                captured("b", npcUuid, UUID.randomUUID())
        ));

        assertFalse(set.isConflictFree());
        assertEquals("conflicting-owner-evidence", set.conflicts().getFirst().reason());
        assertTrue(set.evidence().isEmpty());
    }

    @Test
    void duplicatePhysicalLocationsQuarantineInsteadOfChoosingOne() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                physical("a", npcUuid, ownerUuid, "default", 0, 0),
                physical("b", npcUuid, ownerUuid, "default", 1, 0)
        ));

        assertFalse(set.isConflictFree());
        assertEquals("duplicate-physical-identity", set.conflicts().getFirst().reason());
    }

    @Test
    void ownerlessProfileRecordIsNeutralWhenCapturedEvidenceNamesAnOwner() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                dormant("profile", npcUuid, null, CompanionPopulationEvidence.Kind.PROFILE_RECORD),
                dormant("capture", npcUuid, ownerUuid, CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT)
        ));

        assertTrue(set.isConflictFree());
        CompanionPopulationEvidenceSet.ResolvedEvidence resolved = set.evidence().getFirst();
        assertEquals(ownerUuid, resolved.observedOwnerUuid());
        assertTrue(resolved.ownerObserved());
        assertEquals(CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT, resolved.lifecycleKind());
    }

    @Test
    void ownerlessProfileRecordAloneDoesNotAffirmAnUnownedCompanion() {
        UUID npcUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                dormant("profile", npcUuid, null, CompanionPopulationEvidence.Kind.PROFILE_RECORD)
        ));

        assertTrue(set.isConflictFree());
        assertFalse(set.evidence().getFirst().ownerObserved());
    }

    @Test
    void conflictingActiveDormantKindsQuarantineInsteadOfApplyingPriority() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                dormant("death", npcUuid, ownerUuid, CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT),
                dormant("coop", npcUuid, ownerUuid, CompanionPopulationEvidence.Kind.COOP_SNAPSHOT)
        ));

        assertFalse(set.isConflictFree());
        assertEquals("conflicting-dormant-lifecycle-evidence", set.conflicts().getFirst().reason());
    }

    @Test
    void conflictFilteringRetainsHealthyEvidenceAndEveryProjectionMarker() {
        UUID conflict = new UUID(0L, 501L);
        UUID healthy = new UUID(0L, 502L);
        String fingerprint = projectionFingerprint();
        CompanionPopulationEvidence marker = projection(
                "marker", fingerprint, conflict, conflict, 3, 4
        );
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                captured("owner-a", conflict, new UUID(0L, 601L)),
                captured("owner-b", conflict, new UUID(0L, 602L)),
                physical("healthy", healthy, new UUID(0L, 603L), "default", 1, 2),
                marker
        ));

        CompanionPopulationEvidenceSet filtered = set.excludingConflictUuids(Set.of(conflict));

        assertTrue(filtered.isConflictFree());
        assertEquals(List.of(healthy), filtered.evidence().stream()
                .map(CompanionPopulationEvidenceSet.ResolvedEvidence::npcUuid).toList());
        assertEquals(marker, filtered.projectionObservations(fingerprint).getFirst().evidence());
        assertTrue(filtered.observations(conflict).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> set.excludingConflictUuids(Set.of(UUID.randomUUID())));
    }

    @Test
    void capturedItemCorroboratesCapturedSnapshotWithoutCreatingAmbiguity() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                captured("item", npcUuid, ownerUuid),
                dormant("snapshot", npcUuid, ownerUuid, CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT)
        ));

        assertTrue(set.isConflictFree());
        assertEquals(CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT,
                set.evidence().getFirst().lifecycleKind());
    }

    /** Protects support bundle 6d755cb8: pre-marker clear-owner items retain only a weak source hint. */
    @Test
    void capturedSnapshotOverridesLegacySourceOwnerHintAfterRestart() {
        UUID npcUuid = UUID.randomUUID();
        UUID formerOwner = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                dormant(
                        "legacy-item",
                        npcUuid,
                        formerOwner,
                        CompanionPopulationEvidence.Kind.CAPTURED_ITEM_LEGACY_OWNER_HINT
                ),
                dormant("snapshot", npcUuid, null, CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT)
        ));

        assertTrue(set.isConflictFree());
        CompanionPopulationEvidenceSet.ResolvedEvidence resolved = set.evidence().getFirst();
        assertTrue(resolved.ownerObserved());
        assertEquals(null, resolved.observedOwnerUuid());
        assertEquals(CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT, resolved.lifecycleKind());
    }

    @Test
    void standaloneLegacySourceOwnerHintRemainsConservative() {
        UUID npcUuid = UUID.randomUUID();
        UUID formerOwner = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                dormant(
                        "legacy-item",
                        npcUuid,
                        formerOwner,
                        CompanionPopulationEvidence.Kind.CAPTURED_ITEM_LEGACY_OWNER_HINT
                )
        ));

        assertTrue(set.isConflictFree());
        assertEquals(formerOwner, set.evidence().getFirst().observedOwnerUuid());
    }

    @Test
    void capturedSnapshotStillConflictsWithExplicitItemOwner() {
        UUID npcUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                captured("explicit-item", npcUuid, UUID.randomUUID()),
                dormant("snapshot", npcUuid, null, CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT)
        ));

        assertFalse(set.isConflictFree());
        assertEquals("conflicting-owner-evidence", set.conflicts().getFirst().reason());
    }

    @Test
    void savedCorpseAndDeathSnapshotResolveAsOneDeadNonliveRepresentation() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                deadPhysical("corpse", npcUuid, ownerUuid, "default", 2, 3),
                dormant("snapshot", npcUuid, ownerUuid, CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT)
        ));

        assertTrue(set.isConflictFree());
        CompanionPopulationEvidenceSet.ResolvedEvidence resolved = set.evidence().getFirst();
        assertTrue(resolved.physical());
        assertTrue(resolved.deathObserved());
        assertFalse(resolved.livePhysical());
        assertEquals(CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY, resolved.lifecycleKind());
    }

    @Test
    void liveAndDeadObservationsForTheSameUuidQuarantineReviveAmbiguity() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                physical("live", npcUuid, ownerUuid, "default", 2, 3),
                deadPhysical("corpse", npcUuid, ownerUuid, "default", 2, 3)
        ));

        assertFalse(set.isConflictFree());
        assertEquals("conflicting-physical-death-evidence", set.conflicts().getFirst().reason());
    }

    @Test
    void exactMarkerEvidenceIsIndexedOutsideOrdinaryRepairEvidence() {
        String fingerprint = projectionFingerprint();
        UUID planned = new UUID(0L, 101L);
        CompanionPopulationEvidence marker = projection(
                "exact", fingerprint, planned, planned, 2, 3
        );
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(marker));

        assertTrue(set.evidence().isEmpty());
        assertTrue(set.byNpcUuid().isEmpty());
        assertTrue(set.observations(planned).isEmpty());
        CompanionPopulationEvidenceSet.ProjectionObservation observation =
                set.projectionObservations(fingerprint).getFirst();
        assertEquals(marker, observation.evidence());
        assertEquals(planned, observation.componentUuid());
        assertEquals(planned, observation.legacyNpcUuid());
        assertEquals("default", observation.evidence().physicalWorldName());
        assertTrue(observation.evidence().ownerObserved());
        assertThrows(UnsupportedOperationException.class,
                () -> set.projectionObservations(fingerprint).add(observation));
    }

    @Test
    void alternateMarkerIdentityRemainsVisibleByExactFingerprint() {
        String fingerprint = projectionFingerprint();
        UUID alternate = new UUID(0L, 102L);
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                projection("alternate", fingerprint, alternate, alternate, 4, 5)
        ));

        CompanionPopulationEvidenceSet.ProjectionObservation observation =
                set.projectionObservations(fingerprint).getFirst();
        assertEquals(alternate, observation.componentUuid());
        assertEquals(alternate, observation.legacyNpcUuid());
        assertTrue(set.evidence().isEmpty());
    }

    @Test
    void duplicateMarkerEvidencePreservesEveryUnderlyingObservation() {
        String fingerprint = projectionFingerprint();
        UUID planned = new UUID(0L, 103L);
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                projection("first", fingerprint, planned, planned, 6, 7),
                projection("second", fingerprint, planned, planned, 8, 9)
        ));

        assertEquals(2, set.projectionObservations(fingerprint).size());
        assertTrue(set.conflicts().isEmpty());
        assertTrue(set.evidence().isEmpty());
    }

    @Test
    void legacyMismatchRetainsBothIdentitiesForFailClosedRecovery() {
        String fingerprint = projectionFingerprint();
        UUID componentUuid = new UUID(0L, 104L);
        UUID legacyUuid = new UUID(0L, 105L);
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                projection("mismatch", fingerprint, componentUuid, legacyUuid, 10, 11)
        ));

        CompanionPopulationEvidenceSet.ProjectionObservation observation =
                set.projectionObservations(fingerprint).getFirst();
        assertEquals(componentUuid, observation.componentUuid());
        assertEquals(legacyUuid, observation.legacyNpcUuid());
        assertTrue(set.projectionObservations(projectionFingerprint() + "x").isEmpty());
    }

    @Test
    void corpseMarkerRetainsDeathStateWithoutEnteringOrdinaryRepair() {
        String fingerprint = projectionFingerprint();
        UUID planned = new UUID(0L, 106L);
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(List.of(
                projection("corpse", fingerprint, planned, planned, 12, 13, true)
        ));

        CompanionPopulationEvidenceSet.ProjectionObservation observation =
                set.projectionObservations(fingerprint).getFirst();
        assertTrue(observation.deathObserved());
        assertTrue(observation.evidence().projectionObservation().deathObserved());
        assertTrue(set.byNpcUuid().isEmpty());
        assertTrue(set.evidence().isEmpty());
    }

    static CompanionPopulationEvidence physical(String key,
                                                  UUID npcUuid,
                                                  UUID ownerUuid,
                                                  String world,
                                                  int chunkX,
                                                  int chunkZ) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                world,
                world,
                chunkX,
                chunkZ,
                "test"
        );
    }

    static CompanionPopulationEvidence captured(String key, UUID npcUuid, UUID ownerUuid) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                null,
                null,
                null,
                null,
                "test"
        );
    }

    static CompanionPopulationEvidence deadPhysical(String key,
                                                      UUID npcUuid,
                                                      UUID ownerUuid,
                                                      String world,
                                                      int chunkX,
                                                      int chunkZ) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY,
                world,
                world,
                chunkX,
                chunkZ,
                "test"
        );
    }

    static CompanionPopulationEvidence dormant(String key,
                                                 UUID npcUuid,
                                                 UUID ownerUuid,
                                                 CompanionPopulationEvidence.Kind kind) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                kind,
                null,
                null,
                null,
                null,
                "test"
        );
    }

    private static CompanionPopulationEvidence projection(
            String key,
            String fingerprint,
            UUID componentUuid,
            UUID legacyUuid,
            int chunkX,
            int chunkZ
    ) {
        return projection(
                key, fingerprint, componentUuid, legacyUuid, chunkX, chunkZ, false
        );
    }

    private static CompanionPopulationEvidence projection(
            String key,
            String fingerprint,
            UUID componentUuid,
            UUID legacyUuid,
            int chunkX,
            int chunkZ,
            boolean deathObserved
    ) {
        return new CompanionPopulationEvidence(
                CompanionProjectionEvidence.appendToEvidenceKey(
                        key, fingerprint, componentUuid, legacyUuid, deathObserved
                ),
                componentUuid,
                null,
                true,
                CompanionPopulationEvidence.Kind.PROJECTION_MARKER,
                "default",
                "default",
                chunkX,
                chunkZ,
                "test"
        );
    }

    private static String projectionFingerprint() {
        return CompanionProjectionEvidence.fingerprint(
                "profile-projection",
                "operation-projection",
                "BREEDING_CHILD",
                "child-0000",
                new UUID(0L, 99L),
                1L
        );
    }
}
