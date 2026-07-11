package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
