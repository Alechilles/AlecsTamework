package com.alechilles.alecstamework.npc.actions;

/** Limits a delayed litter to nearby population headroom observed at birth. */
final class BreedingNearbyPopulationAllowance {

    int limit(int requested, int existing, int maxNearby) {
        int sanitizedRequested = Math.max(0, requested);
        if (maxNearby <= 0) {
            return sanitizedRequested;
        }
        int remaining = Math.max(0, maxNearby - Math.max(0, existing));
        return Math.min(sanitizedRequested, remaining);
    }
}
