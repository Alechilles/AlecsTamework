package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Regression coverage for transient avatar-flight parking roles in persistence snapshots. */
class AvatarFlightSnapshotRoleResolverTest {
    @Test
    void parkedSourceUsesItsOriginalRoleInsteadOfEmptyRole() {
        AvatarFlightSourceComponent source = new AvatarFlightSourceComponent(
                "rider", "Tamed_NordicDrake", 7
        );

        assertEquals(
                "Tamed_NordicDrake",
                AvatarFlightSnapshotRoleResolver.resolve(
                        "Empty_Role", source
                )
        );
    }

    @Test
    void intentionalNonParkingRoleChangeRemainsAuthoritative() {
        AvatarFlightSourceComponent source = new AvatarFlightSourceComponent(
                "rider", "Tamed_Miniwyvern", 3
        );

        assertEquals(
                "Tamed_Miniwyvern_Flying",
                AvatarFlightSnapshotRoleResolver.resolve(
                        "Tamed_Miniwyvern_Flying", source
                )
        );
    }

    @Test
    void emptyRoleWithoutAvatarFlightSourceRemainsUnchanged() {
        assertEquals(
                "Empty_Role",
                AvatarFlightSnapshotRoleResolver.resolve("Empty_Role", null)
        );
    }
}
