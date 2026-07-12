package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the durable unowned profile baseline that an exact breeding replay may reuse.
 *
 * <p>Partial or conflicting durable evidence is never treated as a fresh child. Doing so could
 * admit a second projection while the original prepared child still exists.</p>
 */
public final class BreedingPopulationRetryBaselineResolver {
    public static final String CONFLICT_REASON = "breeding-replay-baseline-conflict";

    private final OwnerPopulationIndex ownerIndex;
    private final ClaimOccupancyIndex claimIndex;
    private final CompanionIdentityResolver identityResolver;

    public BreedingPopulationRetryBaselineResolver(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull CompanionIdentityResolver identityResolver
    ) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    }

    /**
     * Returns an exact reusable baseline for replay, or an empty baseline for a genuinely fresh
     * child. Any mixed evidence fails closed.
     */
    @Nonnull
    public Baseline resolve(
            @Nonnull PreparedBreedingPopulationBatch.ReservedChild child,
            @Nonnull ClaimChunkCoordinate destination,
            boolean replaying
    ) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(destination, "destination");
        if (!replaying) {
            return Baseline.fresh();
        }

        OwnerPopulationEntry owner = ownerIndex.entry(child.profileId()).orElse(null);
        ClaimOccupancyEntry claim = claimIndex.entry(child.profileId()).orElse(null);
        UUID currentNpcUuid = identityResolver.currentNpcUuid(child.profileId()).orElse(null);
        if (owner == null && claim == null && currentNpcUuid == null) {
            return Baseline.fresh();
        }
        validateReusable(child, destination, owner, claim, currentNpcUuid);
        return new Baseline(owner, claim);
    }

    private static void validateReusable(
            PreparedBreedingPopulationBatch.ReservedChild child,
            ClaimChunkCoordinate destination,
            OwnerPopulationEntry owner,
            ClaimOccupancyEntry claim,
            UUID currentNpcUuid
    ) {
        if (owner == null || claim == null) {
            throw conflict(child, "incomplete population baseline");
        }
        if (!child.plannedNpcUuid().equals(currentNpcUuid)) {
            throw conflict(child, "current NPC UUID does not match the planned child");
        }
        if (owner.ownerId() != null || claim.ownerId() != null) {
            throw conflict(child, "population baseline is already owned");
        }
        if (owner.lifecycleState() != CompanionLifecycleState.ACTIVE
                || claim.lifecycleState() != CompanionLifecycleState.ACTIVE) {
            throw conflict(child, "population baseline is not active");
        }
        if (owner.revision() != claim.revision()) {
            throw conflict(child, "owner and claim revisions differ");
        }
        if (owner.revision() != 0L) {
            throw conflict(child, "population baseline is not the untouched revision-zero birth plan");
        }
        String destinationWorld = OwnerPopulationScopeKey.normalizeWorldName(destination.worldName());
        if (!Objects.equals(owner.ownershipWorldName(), destinationWorld)) {
            throw conflict(child, "ownership world differs from the replay destination");
        }
        if (!destination.equals(claim.physicalChunk())) {
            throw conflict(child, "physical claim coordinate differs from the replay destination");
        }
    }

    private static IllegalStateException conflict(
            PreparedBreedingPopulationBatch.ReservedChild child,
            String detail
    ) {
        return new IllegalStateException(
                CONFLICT_REASON + ": " + detail + " [profileId=" + child.profileId() + "]"
        );
    }

    /** Existing durable records to reuse; both values are null for a fresh admission. */
    public record Baseline(
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim
    ) {
        private static Baseline fresh() {
            return new Baseline(null, null);
        }

        public boolean reusable() {
            return owner != null && claim != null;
        }
    }
}
