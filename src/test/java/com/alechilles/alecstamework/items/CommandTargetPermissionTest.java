package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests command target permission parity with player damage constraints. */
class CommandTargetPermissionTest {

    @Test
    void blocksSelfAndCommandedNpcTargets() {
        assertFalse(CommandTargetPermission.isAllowed(
                true, false, false, false, true, false, false, false, false
        ));
        assertFalse(CommandTargetPermission.isAllowed(
                false, true, false, false, true, false, false, false, false
        ));
    }

    @Test
    void blocksOwnedTargetsForCommander() {
        assertFalse(CommandTargetPermission.isAllowed(
                false, false, true, false, true, false, true, false, false
        ));
    }

    @Test
    void blocksPlayerTargetsWhenPvpDisallowedOrSpawnProtected() {
        assertFalse(CommandTargetPermission.isAllowed(
                false, false, false, true, false, false, false, false, false
        ));
        assertFalse(CommandTargetPermission.isAllowed(
                false, false, false, true, true, true, false, false, false
        ));
    }

    @Test
    void blocksOwnedTargetsWhenGlobalOwnedProtectionIsEnabled() {
        assertFalse(CommandTargetPermission.isAllowed(
                false, false, false, false, true, false, true, true, false
        ));
        assertFalse(CommandTargetPermission.isAllowed(
                false, false, false, false, true, false, true, false, true
        ));
    }

    @Test
    void allowsNormalHostileNpcTargets() {
        assertTrue(CommandTargetPermission.isAllowed(
                false, false, false, false, true, false, false, false, false
        ));
    }
}
