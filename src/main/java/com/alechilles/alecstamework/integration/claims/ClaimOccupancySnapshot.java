package com.alechilles.alecstamework.integration.claims;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Immutable, revisioned view of committed canonical occupancy.
 */
public record ClaimOccupancySnapshot(long revision,
                                     @Nonnull Map<String, ClaimOccupancyEntry> entriesByProfile,
                                     @Nonnull Map<ClaimChunkCoordinate, Set<String>> profilesByChunk) {
    public ClaimOccupancySnapshot {
        entriesByProfile = Map.copyOf(entriesByProfile);
        Map<ClaimChunkCoordinate, Set<String>> copiedChunks = new HashMap<>();
        for (Map.Entry<ClaimChunkCoordinate, Set<String>> entry : profilesByChunk.entrySet()) {
            copiedChunks.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        profilesByChunk = Map.copyOf(copiedChunks);
    }

    @Nonnull
    public Set<String> profilesIn(@Nonnull ClaimFootprint footprint) {
        LinkedHashSet<String> profiles = new LinkedHashSet<>();
        for (ClaimChunkCoordinate chunk : footprint.chunks()) {
            profiles.addAll(profilesByChunk.getOrDefault(chunk, Set.of()));
        }
        return Set.copyOf(profiles);
    }

    public int occupiedProfileCount() {
        int count = 0;
        for (ClaimOccupancyEntry entry : entriesByProfile.values()) {
            if (entry.occupiesClaim()) {
                count++;
            }
        }
        return count;
    }
}
