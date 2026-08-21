package com.alechilles.alecstamework.npc.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for temporary Empty_Role presentation while an NPC is mounted. */
class MountedNpcSnapshotRoleResolverTest {

    @Test
    void nativeMountUsesSavedOriginalRoleDuringEmptyRoleParking() {
        MountedNpcSnapshotRoleResolver.Resolution result =
                MountedNpcSnapshotRoleResolver.resolveNativeParking(
                        "Empty_Role",
                        27,
                        index -> index == 27 ? "Cow_Default" : null
                );

        assertEquals("Cow_Default", result.roleId());
        assertTrue(result.temporarilyParked());
    }

    @Test
    void nonParkingRoleRemainsAuthoritative() {
        MountedNpcSnapshotRoleResolver.Resolution result =
                MountedNpcSnapshotRoleResolver.resolveNativeParking(
                        "Cow_Variant",
                        27,
                        index -> "Cow_Default"
                );

        assertEquals("Cow_Variant", result.roleId());
        assertFalse(result.temporarilyParked());
    }
}
