package com.alechilles.alecstamework.persistence.authoring.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import org.junit.jupiter.api.Test;

/** Guards timed-summon persistence against avatar-flight's parked empty role. */
class HytaleTimedWorldEvidenceReaderTest {
    @Test
    void parkedAvatarFlightSourcePreservesItsOriginalRoleInStoredSnapshot() {
        AvatarFlightSourceComponent source = new AvatarFlightSourceComponent(
                "rider", "Tamed_NordicDrake", 7
        );

        assertEquals(
                "Tamed_NordicDrake",
                HytaleTimedWorldEvidenceReader.resolveSnapshotRoleId(
                        "Empty_Role", source
                )
        );
    }

    @Test
    void ordinarySourceKeepsItsLiveRoleInStoredSnapshot() {
        assertEquals(
                "Tamed_NordicDrake",
                HytaleTimedWorldEvidenceReader.resolveSnapshotRoleId(
                        "Tamed_NordicDrake", null
                )
        );
    }

    @Test
    void parkedStoredSnapshotUsesTheProfileRoleForRecovery() {
        assertEquals(
                "Tamed_NordicDrake",
                HytaleTimedWorldEvidenceReader.resolveStoredSnapshotRoleId(
                        "Empty_Role", "Tamed_NordicDrake"
                )
        );
    }

    @Test
    void ordinaryStoredSnapshotKeepsItsRecordedRole() {
        assertEquals(
                "Tamed_NordicDrake",
                HytaleTimedWorldEvidenceReader.resolveStoredSnapshotRoleId(
                        "Tamed_NordicDrake", "Tamed_NordicDrake"
                )
        );
    }
}
