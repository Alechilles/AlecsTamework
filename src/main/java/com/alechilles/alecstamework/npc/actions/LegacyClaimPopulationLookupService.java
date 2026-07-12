package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancySnapshot;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationSnapshot;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationSnapshotService;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Compatibility-only live claim lookup used by the legacy tame policy adapter. */
final class LegacyClaimPopulationLookupService {
    private final ClaimIntegrationBridge claimBridge;
    @Nullable
    private final ClaimOccupancyIndex occupancyIndex;
    private final ClaimPopulationSnapshotService snapshotService;

    LegacyClaimPopulationLookupService(@Nonnull ClaimIntegrationBridge claimBridge,
                                       @Nullable ClaimOccupancyIndex occupancyIndex) {
        this.claimBridge = Objects.requireNonNull(claimBridge, "claimBridge");
        this.occupancyIndex = occupancyIndex;
        this.snapshotService = new ClaimPopulationSnapshotService();
    }

    @Nonnull
    BreedingClaimLimitPolicyService.ResolvedClaim resolveClaim(
            @Nullable String worldName,
            @Nullable Vector3d position
    ) {
        if (worldName == null || worldName.isBlank() || position == null) {
            return resolved(BreedingClaimLimitPolicyService.ClaimResolutionStatus.ERROR, null, 0,
                    "missing-world-or-position");
        }
        ClaimLookupResult lookup = claimBridge.lookupClaim(worldName, position);
        if (lookup == null) {
            return resolved(BreedingClaimLimitPolicyService.ClaimResolutionStatus.ERROR, null, 0,
                    "lookup-result-null");
        }
        return switch (lookup.status()) {
            case CLAIM_FOUND -> lookup.key() == null
                    ? resolved(BreedingClaimLimitPolicyService.ClaimResolutionStatus.ERROR, null, 0,
                    "claim-key-missing")
                    : resolved(
                    BreedingClaimLimitPolicyService.ClaimResolutionStatus.CLAIM_FOUND,
                    BreedingClaimLimitPolicyService.ClaimReservationKey.fromPopulationKey(lookup.key()),
                    Math.max(0, lookup.claimChunkCount()),
                    null
            );
            case NO_CLAIM -> resolved(
                    BreedingClaimLimitPolicyService.ClaimResolutionStatus.NO_CLAIM, null, 0, null
            );
            case UNAVAILABLE -> resolved(
                    BreedingClaimLimitPolicyService.ClaimResolutionStatus.UNAVAILABLE, null, 0, lookup.message()
            );
            case ERROR -> resolved(
                    BreedingClaimLimitPolicyService.ClaimResolutionStatus.ERROR, null, 0, lookup.message()
            );
        };
    }

    @Nonnull
    BreedingClaimLimitPolicyService.CountResult countOwnedPopulationInClaim(
            @Nonnull Store<EntityStore> store,
            @Nonnull String worldName,
            @Nonnull BreedingClaimLimitPolicyService.ClaimReservationKey targetClaim
    ) {
        return countOwnedPopulationInClaim(worldName, targetClaim);
    }

    @Nonnull
    BreedingClaimLimitPolicyService.CountResult countOwnedPopulationInClaim(
            @Nonnull String worldName,
            @Nonnull BreedingClaimLimitPolicyService.ClaimReservationKey targetClaim
    ) {
        if (occupancyIndex == null) {
            return failure("owner population runtime unavailable");
        }
        ClaimOccupancyReadiness readiness = occupancyIndex.readiness();
        if (!readiness.allowsPositiveAdmissions()) {
            return failure("claim occupancy index not ready: " + readiness.name().toLowerCase());
        }
        ClaimPopulationKey targetKey = populationKey(worldName, targetClaim);
        if (targetKey == null) {
            return failure("target claim identity was invalid");
        }
        if (!claimBridge.isAvailable()) {
            return failure("claim provider unavailable: " + claimBridge.getUnavailableReason());
        }

        ClaimOccupancySnapshot occupancy = occupancyIndex.snapshot();
        if (!occupancyIndex.readiness().allowsPositiveAdmissions()) {
            return failure("claim occupancy index became unavailable during snapshot");
        }
        ClaimLookupSession lookupSession = new ClaimLookupSession(readyContext(), false);
        ClaimPopulationSnapshot population = snapshotService.snapshot(
                occupancy,
                ClaimResolution.foundWithoutFootprint(targetKey, 0),
                lookupSession
        );
        return switch (population.status()) {
            case READY -> new BreedingClaimLimitPolicyService.CountResult(
                    true, population.population(), null
            );
            case NO_CLAIM -> failure("target claim disappeared during population snapshot");
            case UNAVAILABLE, ERROR -> failure(population.message());
        };
    }

    @Nonnull
    private static BreedingClaimLimitPolicyService.ResolvedClaim resolved(
            @Nonnull BreedingClaimLimitPolicyService.ClaimResolutionStatus status,
            @Nullable BreedingClaimLimitPolicyService.ClaimReservationKey key,
            int chunkCount,
            @Nullable String message
    ) {
        return new BreedingClaimLimitPolicyService.ResolvedClaim(status, key, chunkCount, message);
    }

    @Nullable
    private ClaimPopulationKey populationKey(
            @Nonnull String worldName,
            @Nonnull BreedingClaimLimitPolicyService.ClaimReservationKey targetClaim
    ) {
        if (worldName.isBlank()
                || !worldName.equals(targetClaim.worldName())
                || !claimBridge.providerId().equals(targetClaim.providerId())
                || targetClaim.ownerType() == null
                || targetClaim.ownerId() == null) {
            return null;
        }
        return new ClaimPopulationKey(
                targetClaim.providerId(),
                worldName,
                targetClaim.ownerType(),
                targetClaim.ownerId(),
                targetClaim.claimId()
        );
    }

    @Nonnull
    private ClaimPolicyContext readyContext() {
        return new ClaimPolicyContext(
                claimBridge.providerId(),
                ClaimIntegrationProvider.AUTO,
                ClaimIntegrationProvider.AUTO,
                claimBridge.providerId(),
                ClaimProviderState.READY,
                Set.of(ClaimProviderCapability.STABLE_CLAIM_IDENTITY),
                null,
                null,
                ClaimProviderGeneration.NONE,
                0L,
                claimBridge
        );
    }

    @Nonnull
    private static BreedingClaimLimitPolicyService.CountResult failure(@Nullable String message) {
        return new BreedingClaimLimitPolicyService.CountResult(
                false,
                0,
                message == null || message.isBlank() ? "claim population snapshot failed" : message
        );
    }
}
