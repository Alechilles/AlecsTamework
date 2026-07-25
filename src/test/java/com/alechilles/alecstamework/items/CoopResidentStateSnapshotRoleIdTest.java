package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for exact-case role identity in frozen companion snapshots. */
class CoopResidentStateSnapshotRoleIdTest {

    @Test
    void roleIdentityTrimsWithoutChangingHytaleAssetCase() {
        assertEquals(
                "NordicDrake",
                CoopResidentStateSnapshotService.normalizeRoleId(
                        " NordicDrake "
                )
        );
    }

    @Test
    void missingRoleIdentityRemainsAbsent() {
        assertNull(CoopResidentStateSnapshotService.normalizeRoleId(null));
        assertNull(CoopResidentStateSnapshotService.normalizeRoleId("  "));
    }
}
