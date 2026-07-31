package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Repairs bonded snapshots corrupted by Avatar Flight's transient parking role. */
class BondedCompanionAvatarFlightRoleRepairTest {
    private static final UUID OWNER = UUID.fromString(
            "20000000-0000-0000-0000-000000000001"
    );

    @Test
    void storedParkingRoleRepairsFromBondedProfileIdentity() {
        BondedCompanionSnapshot repaired = BondedCompanionCoreApiOperations
                .repairStoredSnapshotRole(snapshot("Empty_Role"),
                        "Tamed_Miniwyvern");

        assertEquals("Tamed_Miniwyvern", repaired.fullState().roleId());
    }

    @Test
    void intentionalStoredRoleSwapRemainsAuthoritative() {
        BondedCompanionSnapshot repaired = BondedCompanionCoreApiOperations
                .repairStoredSnapshotRole(
                        snapshot("Tamed_Miniwyvern_Flying"),
                        "Tamed_Miniwyvern"
                );

        assertEquals(
                "Tamed_Miniwyvern_Flying",
                repaired.fullState().roleId()
        );
    }

    private BondedCompanionSnapshot snapshot(String roleId) {
        return BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshot(
                        UUID.fromString(
                                "10000000-0000-0000-0000-000000000001"
                        ),
                        null,
                        -1,
                        roleId,
                        null,
                        new TameworkOwnerComponent(OWNER, "owner"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        -99L
                ),
                Map.of()
        );
    }
}
