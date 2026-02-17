package com.alechilles.alecstamework.items;

import java.util.UUID;

/**
 * Ownership gate used by naming interactions.
 */
final class NamingOwnershipPolicy {
    private NamingOwnershipPolicy() {
    }

    static boolean canName(UUID playerUuid, UUID ownerUuid, NamingRules rules) {
        if (rules == null) {
            return false;
        }
        if (!rules.isRequireOwner()) {
            return true;
        }
        if (ownerUuid == null) {
            return rules.isAllowUnownedWhenRequireOwner();
        }
        return playerUuid != null && ownerUuid.equals(playerUuid);
    }
}
