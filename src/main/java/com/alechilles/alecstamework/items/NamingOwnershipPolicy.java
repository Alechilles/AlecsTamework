package com.alechilles.alecstamework.items;

import java.util.UUID;

/**
 * Ownership gate used by naming interactions.
 */
final class NamingOwnershipPolicy {
    private NamingOwnershipPolicy() {
    }

    /**
     * {@code mayRename} is the caller's answer to "may this player rename this owned NPC": the owner,
     * or a player the owner shares it with at a level that allows renaming. Ignored for an unowned NPC.
     */
    static boolean canName(UUID ownerUuid, NamingRules rules, boolean mayRename) {
        if (rules == null) {
            return false;
        }
        if (!rules.isRequireOwner()) {
            return true;
        }
        if (ownerUuid == null) {
            return rules.isAllowUnownedWhenRequireOwner();
        }
        return mayRename;
    }
}
