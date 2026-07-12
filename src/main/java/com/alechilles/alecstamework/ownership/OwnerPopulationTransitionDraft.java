package com.alechilles.alecstamework.ownership;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure derived state for one proposed owner transition.
 *
 * <p>The draft centralizes scope-delta calculation so reservations and commits use the same
 * global and per-world accounting rules.
 */
record OwnerPopulationTransitionDraft(OwnerPopulationEntry current,
                                      OwnerPopulationEntry proposed,
                                      Set<OwnerPopulationScopeKey> additions,
                                      OwnerPopulationScopeKey constrainedKey,
                                      boolean positiveDelta) {

    OwnerPopulationTransitionDraft {
        additions = Set.copyOf(additions);
    }

    static OwnerPopulationTransitionDraft create(OwnerPopulationTransitionRequest request,
                                                 OwnerPopulationEntry current,
                                                 long nextRevision) {
        OwnerPopulationEntry proposed = new OwnerPopulationEntry(
                request.profileId(),
                request.newOwnerId(),
                request.destinationWorldName(),
                request.lifecycleState(),
                nextRevision
        );
        Set<OwnerPopulationScopeKey> additions = scopeKeys(proposed);
        additions.removeAll(scopeKeys(current));
        OwnerPopulationScopeKey constrainedKey = constrainedKey(request);
        boolean positiveDelta = constrainedKey != null
                ? additions.contains(constrainedKey)
                : hasUnscopedOwnerAddition(request, additions);
        return new OwnerPopulationTransitionDraft(
                current,
                proposed,
                additions,
                constrainedKey,
                positiveDelta
        );
    }

    static Set<OwnerPopulationScopeKey> scopeKeys(OwnerPopulationEntry entry) {
        Set<OwnerPopulationScopeKey> keys = new HashSet<>(2);
        if (entry == null || entry.ownerId() == null) {
            return keys;
        }
        keys.add(OwnerPopulationScopeKey.global(entry.ownerId()));
        if (entry.ownershipWorldName() != null) {
            keys.add(OwnerPopulationScopeKey.perWorld(entry.ownerId(), entry.ownershipWorldName()));
        }
        return keys;
    }

    static boolean requiresWorldContext(OwnerPopulationTransitionRequest request,
                                        OwnerPopulationEntry current) {
        return request.limit() > 0
                && !request.force()
                && request.limitScope() == OwnerPopulationLimitScope.PER_WORLD
                && request.newOwnerId() != null
                && request.destinationWorldName() == null
                && (current == null || !request.newOwnerId().equals(current.ownerId()));
    }

    static String reservationReason(OwnerPopulationTransitionRequest request,
                                    boolean positiveDelta) {
        if (!positiveDelta) {
            return "owner-population-zero-delta";
        }
        if (request.force()) {
            return "owner-population-reserved-force";
        }
        return request.limit() <= 0
                ? "owner-population-reserved-disabled"
                : "owner-population-reserved";
    }

    private static OwnerPopulationScopeKey constrainedKey(OwnerPopulationTransitionRequest request) {
        if (request.newOwnerId() == null) {
            return null;
        }
        if (request.limitScope() == OwnerPopulationLimitScope.GLOBAL) {
            return OwnerPopulationScopeKey.global(request.newOwnerId());
        }
        if (request.destinationWorldName() == null) {
            return null;
        }
        return OwnerPopulationScopeKey.perWorld(request.newOwnerId(), request.destinationWorldName());
    }

    private static boolean hasUnscopedOwnerAddition(OwnerPopulationTransitionRequest request,
                                                    Set<OwnerPopulationScopeKey> additions) {
        return request.newOwnerId() != null
                && additions.contains(OwnerPopulationScopeKey.global(request.newOwnerId()));
    }
}
