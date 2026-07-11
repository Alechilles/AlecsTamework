package com.alechilles.alecstamework.integration.claims;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Sums unique canonical physical profiles for an exact footprint or a lookup-only claim.
 */
public final class ClaimPopulationSnapshotService {
    @Nonnull
    public ClaimPopulationSnapshot snapshot(@Nonnull ClaimOccupancyIndex index,
                                            @Nonnull ClaimResolution target,
                                            @Nonnull ClaimLookupSession lookupSession) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lookupSession, "lookupSession");
        ClaimOccupancySnapshot occupancy = index.snapshot();
        return switch (target.status()) {
            case NO_CLAIM -> result(
                    ClaimPopulationSnapshot.Status.NO_CLAIM, null, null, Set.of(), occupancy.revision(), null
            );
            case UNAVAILABLE -> result(
                    ClaimPopulationSnapshot.Status.UNAVAILABLE,
                    null,
                    null,
                    Set.of(),
                    occupancy.revision(),
                    target.message()
            );
            case ERROR -> result(
                    ClaimPopulationSnapshot.Status.ERROR,
                    null,
                    null,
                    Set.of(),
                    occupancy.revision(),
                    target.message()
            );
            case CLAIM_FOUND -> foundSnapshot(occupancy, target, lookupSession);
        };
    }

    @Nonnull
    private ClaimPopulationSnapshot foundSnapshot(@Nonnull ClaimOccupancySnapshot occupancy,
                                                  @Nonnull ClaimResolution target,
                                                  @Nonnull ClaimLookupSession lookupSession) {
        ClaimPopulationKey key = target.key();
        if (key == null) {
            return result(
                    ClaimPopulationSnapshot.Status.ERROR,
                    null,
                    null,
                    Set.of(),
                    occupancy.revision(),
                    "claim-key-missing"
            );
        }
        ClaimFootprint footprint = target.footprint();
        if (footprint != null && !footprint.chunks().isEmpty()) {
            return result(
                    ClaimPopulationSnapshot.Status.READY,
                    key,
                    footprint,
                    occupancy.profilesIn(footprint),
                    occupancy.revision(),
                    null
            );
        }
        return resolveLookupOnlyPopulation(occupancy, key, footprint, lookupSession);
    }

    @Nonnull
    private ClaimPopulationSnapshot resolveLookupOnlyPopulation(@Nonnull ClaimOccupancySnapshot occupancy,
                                                                @Nonnull ClaimPopulationKey targetKey,
                                                                ClaimFootprint footprint,
                                                                @Nonnull ClaimLookupSession lookupSession) {
        LinkedHashSet<String> profiles = new LinkedHashSet<>();
        for (Map.Entry<ClaimChunkCoordinate, Set<String>> entry : occupancy.profilesByChunk().entrySet()) {
            ClaimResolution resolved = lookupSession.resolveChunk(entry.getKey());
            if (resolved.status() == ClaimLookupResult.Status.UNAVAILABLE) {
                return result(
                        ClaimPopulationSnapshot.Status.UNAVAILABLE,
                        targetKey,
                        footprint,
                        Set.of(),
                        occupancy.revision(),
                        resolved.message()
                );
            }
            if (resolved.status() == ClaimLookupResult.Status.ERROR) {
                return result(
                        ClaimPopulationSnapshot.Status.ERROR,
                        targetKey,
                        footprint,
                        Set.of(),
                        occupancy.revision(),
                        resolved.message()
                );
            }
            if (resolved.status() == ClaimLookupResult.Status.CLAIM_FOUND
                    && targetKey.equals(resolved.key())) {
                profiles.addAll(entry.getValue());
            }
        }
        return result(
                ClaimPopulationSnapshot.Status.READY,
                targetKey,
                footprint,
                profiles,
                occupancy.revision(),
                null
        );
    }

    @Nonnull
    private static ClaimPopulationSnapshot result(ClaimPopulationSnapshot.Status status,
                                                  ClaimPopulationKey key,
                                                  ClaimFootprint footprint,
                                                  Set<String> profileIds,
                                                  long revision,
                                                  String message) {
        return new ClaimPopulationSnapshot(status, key, footprint, profileIds, revision, message);
    }
}
