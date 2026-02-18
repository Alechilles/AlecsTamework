package com.alechilles.alecstamework.items;

/**
 * Shared target permission checks for command-item targeting.
 */
final class CommandTargetPermission {
    private CommandTargetPermission() {
    }

    static boolean isAllowed(boolean targetIsCommander,
                             boolean targetIsCommandedNpc,
                             boolean targetOwnedByCommander,
                             boolean targetIsPlayer,
                             boolean playerTargetingAllowed,
                             boolean targetPlayerSpawnProtected,
                             boolean targetHasOwner,
                             boolean blockAllPlayerDamageIfOwned,
                             boolean invulnerableIfOwned) {
        if (targetIsCommander || targetIsCommandedNpc || targetOwnedByCommander) {
            return false;
        }
        if (targetIsPlayer) {
            if (!playerTargetingAllowed || targetPlayerSpawnProtected) {
                return false;
            }
        }
        if (!targetHasOwner) {
            return true;
        }
        if (invulnerableIfOwned || blockAllPlayerDamageIfOwned) {
            return false;
        }
        return true;
    }
}
