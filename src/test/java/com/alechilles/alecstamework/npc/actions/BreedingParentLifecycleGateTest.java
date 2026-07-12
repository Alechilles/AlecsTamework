package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationProfileStateSnapshot;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for callback-time canonical parent lifecycle fencing. */
class BreedingParentLifecycleGateTest {
    private static final UUID NPC_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000321"
    );
    private static final String PROFILE = "parent-profile";

    @Test
    void activeCanonicalParentIsAllowedEvenWhenUnowned() {
        assertTrue(gate(snapshot(
                OwnerPopulationReadiness.READY,
                false,
                false,
                new OwnerPopulationEntry(
                        PROFILE, null, "world", CompanionLifecycleState.ACTIVE, 4L
                )
        )).inspect(identity(PROFILE)).allowed());
    }

    @Test
    void exactSyntheticEntityProfileIsTheOnlyAllowedMissingEntry() {
        BreedingParentLifecycleGate gate = gate(snapshot(
                OwnerPopulationReadiness.READY, false, false, null
        ));

        assertTrue(gate.inspect(identity("entity:" + NPC_UUID)).allowed());
        assertFalse(gate.inspect(identity(PROFILE)).allowed());
        assertFalse(gate.inspect(identity("entity:" + UUID.randomUUID())).allowed());
    }

    @Test
    void everyNonActiveCanonicalLifecycleIsRejected() {
        for (CompanionLifecycleState state : CompanionLifecycleState.values()) {
            if (state == CompanionLifecycleState.ACTIVE) {
                continue;
            }
            OwnerPopulationEntry entry = new OwnerPopulationEntry(
                    PROFILE, null, "world", state, 4L
            );
            assertFalse(gate(snapshot(
                    OwnerPopulationReadiness.READY, false, false, entry
            )).inspect(identity(PROFILE)).allowed(), state.name());
        }
    }

    @Test
    void transitionReloadAndNonReadyAuthorityFailClosed() {
        OwnerPopulationEntry active = new OwnerPopulationEntry(
                PROFILE, null, "world", CompanionLifecycleState.ACTIVE, 4L
        );
        assertFalse(gate(snapshot(
                OwnerPopulationReadiness.READY, false, true, active
        )).inspect(identity(PROFILE)).allowed());
        assertFalse(gate(snapshot(
                OwnerPopulationReadiness.READY, true, false, active
        )).inspect(identity(PROFILE)).allowed());
        for (OwnerPopulationReadiness readiness : OwnerPopulationReadiness.values()) {
            if (readiness != OwnerPopulationReadiness.READY) {
                assertFalse(gate(snapshot(
                        readiness, false, false, active
                )).inspect(identity(PROFILE)).allowed(), readiness.name());
            }
        }
        assertFalse(new BreedingParentLifecycleGate(profile -> null)
                .inspect(identity(PROFILE)).allowed());
    }

    private static BreedingParentLifecycleGate gate(
            OwnerPopulationProfileStateSnapshot snapshot) {
        return new BreedingParentLifecycleGate(profile -> snapshot);
    }

    private static OwnerPopulationProfileStateSnapshot snapshot(
            OwnerPopulationReadiness readiness,
            boolean reload,
            boolean pending,
            OwnerPopulationEntry entry) {
        return new OwnerPopulationProfileStateSnapshot(
                readiness, reload, pending, Optional.ofNullable(entry)
        );
    }

    private static BreedingParentIdentity identity(String profileId) {
        return new BreedingParentIdentity(NPC_UUID, profileId);
    }
}
