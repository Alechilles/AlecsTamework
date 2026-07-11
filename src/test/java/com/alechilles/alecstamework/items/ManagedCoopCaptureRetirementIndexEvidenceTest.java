package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.EvidenceDecision;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.EvidenceStatus;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RemovalObservation;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact cross-index contracts for managed-coop capture source retirement. */
class ManagedCoopCaptureRetirementIndexEvidenceTest {
    private static final UUID SOURCE = new UUID(0L, 61L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world-a", 4, 5, 6);
    private static final String HASH = "b".repeat(64);

    @Test
    void resolvesOnlyExactSourceRetireOperationAndHousedResident() {
        Fixture fixture = fixture(ResidentState.HOUSED, true);

        EvidenceDecision decision = fixture.evidence.resolve(ready(
                OperationState.SOURCE_RETIRE_REQUESTED, 2L));

        assertEquals(EvidenceStatus.ACTIVE, decision.status());
        assertNotNull(decision.command());
        assertEquals("capture-a", decision.command().operationId());
        assertEquals("resident-a", decision.command().residentId());
        assertEquals(0L, decision.command().expectedResidentGeneration());
        assertEquals(AUTHORITY.slotKey(1), decision.command().authoritySlotKey());
    }

    @Test
    void untrustedCompositeOrNonHousedResidentFailsClosed() {
        Fixture untrusted = fixture(ResidentState.HOUSED, true);
        untrusted.compositeTrusted.set(false);
        Fixture deployed = fixture(ResidentState.DEPLOYED, true);

        EvidenceDecision untrustedDecision = untrusted.evidence.resolve(ready(
                OperationState.SOURCE_RETIRE_REQUESTED, 2L));
        EvidenceDecision deployedDecision = deployed.evidence.resolve(ready(
                OperationState.SOURCE_RETIRE_REQUESTED, 2L));

        assertEquals(EvidenceStatus.REJECTED, untrustedDecision.status());
        assertTrue(untrustedDecision.detail().contains("untrusted"));
        assertEquals(EvidenceStatus.REJECTED, deployedDecision.status());
        assertTrue(deployedDecision.detail().contains("identity_mismatch"));
    }

    @Test
    void removalRequiresExactPersistentMarkerFields() {
        Fixture fixture = fixture(ResidentState.HOUSED, true);
        RemovalObservation wrongGeneration = new RemovalObservation(
                SOURCE,
                "profile-a",
                "capture-a",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE,
                AUTHORITY.slotKey(1),
                SOURCE,
                1L
        );

        EvidenceDecision rejected = fixture.evidence.resolve(wrongGeneration);
        EvidenceDecision accepted = fixture.evidence.resolve(removal());

        assertEquals(EvidenceStatus.REJECTED, rejected.status());
        assertEquals(EvidenceStatus.ACTIVE, accepted.status());
    }

    @Test
    void terminalReplayIsIdempotentOnlyAfterActiveOperationDisappears() {
        Fixture fixture = fixture(ResidentState.HOUSED, false);

        EvidenceDecision decision = fixture.evidence.resolve(
                ready(OperationState.COMPLETE, 3L));

        assertEquals(EvidenceStatus.ALREADY_COMPLETE, decision.status());
    }

    private static Fixture fixture(ResidentState residentState, boolean activeOperation) {
        ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        ManagedCoopLifecycleOperationIndex operations =
                new ManagedCoopLifecycleOperationIndex();
        AuthorityRecord authority = new AuthorityRecord(
                AUTHORITY.authorityId(), AUTHORITY, "coop-a", AuthorityState.TWORK_MANAGED,
                true, 0, -500L, -500L, null
        );
        UUID deployedUuid = residentState == ResidentState.DEPLOYED ? SOURCE : null;
        ResidentRecord resident = new ResidentRecord(
                "resident-a", AUTHORITY, "coop-a", 1, "profile-a", "tamed_test",
                SOURCE, SOURCE, deployedUuid, "{}", HASH, 1, residentState,
                0L, true, -500L, 0L, -500L, -500L
        );
        OperationRecord operation = new OperationRecord(
                "capture-a", OperationKind.CAPTURE, "profile-a", AUTHORITY,
                "coop-a", 1, SOURCE, null, null,
                OperationState.SOURCE_RETIRE_REQUESTED, HASH,
                0L, 2L, 0, true, -500L, -400L, 0L, null
        );
        assertTrue(residents.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority)),
                ManagedCoopReadResult.loaded(List.of(resident))).rebuilt());
        assertTrue(operations.rebuild(ManagedCoopReadResult.loaded(
                activeOperation ? List.of(operation) : List.of())).rebuilt());
        AtomicBoolean compositeTrusted = new AtomicBoolean(true);
        return new Fixture(
                compositeTrusted,
                new ManagedCoopCaptureRetirementIndexEvidence(
                        compositeTrusted::get, residents, operations)
        );
    }

    private static RetirementReady ready(OperationState state, long generation) {
        return new RetirementReady(
                SOURCE, "profile-a", "resident-a", "capture-a", AUTHORITY,
                "coop-a", 1, HASH, generation, state, 1L
        );
    }

    private static RemovalObservation removal() {
        return new RemovalObservation(
                SOURCE,
                "profile-a",
                "capture-a",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE,
                AUTHORITY.slotKey(1),
                SOURCE,
                2L
        );
    }

    private record Fixture(AtomicBoolean compositeTrusted,
                           ManagedCoopCaptureRetirementIndexEvidence evidence) {
    }
}
