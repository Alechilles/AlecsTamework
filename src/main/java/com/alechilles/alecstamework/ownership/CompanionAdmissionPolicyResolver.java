package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationActivation;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRequest;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves one immutable owner/claim settings and occupancy view for a scheduled mutation. */
final class CompanionAdmissionPolicyResolver {
    private final ClaimOccupancyIndex claimOccupancyIndex;
    private final ClaimProviderRegistry claimProviderRegistry;

    CompanionAdmissionPolicyResolver(@Nonnull ClaimOccupancyIndex claimOccupancyIndex,
                                     @Nonnull ClaimProviderRegistry claimProviderRegistry) {
        this.claimOccupancyIndex = Objects.requireNonNull(claimOccupancyIndex, "claimOccupancyIndex");
        this.claimProviderRegistry = Objects.requireNonNull(claimProviderRegistry, "claimProviderRegistry");
    }

    @Nonnull
    ClaimOccupancyTransition transition(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot,
                                        @Nullable UUID newOwnerId,
                                        @Nonnull CompanionLifecycleState lifecycleState) {
        ClaimOccupancyEntry expected = claimOccupancyIndex.entry(snapshot.profileId()).orElse(null);
        long proposedRevision = expected == null ? 1L : incrementRevision(expected.revision());
        ClaimOccupancyEntry proposed = new ClaimOccupancyEntry(
                snapshot.profileId(),
                newOwnerId,
                lifecycleState,
                new ClaimChunkCoordinate(snapshot.worldName(), snapshot.chunkX(), snapshot.chunkZ()),
                proposedRevision
        );
        return new ClaimOccupancyTransition(expected, proposed);
    }

    @Nonnull
    ClaimAdmissionRequest request(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot,
                                  @Nullable UUID newOwnerId,
                                  @Nonnull CompanionLifecycleState lifecycleState,
                                  @Nonnull OwnerPopulationOperation operation,
                                  @Nonnull ClaimOccupancyTransition transition,
                                  @Nonnull Policy policy,
                                  boolean force) {
        return new ClaimAdmissionRequest(
                claimOperation(operation),
                List.of(transition),
                (newOwnerId == null && !policy.requireClaim()) || !occupiesClaim(lifecycleState)
                        ? null
                        : new ClaimChunkCoordinate(
                                snapshot.worldName(), snapshot.chunkX(), snapshot.chunkZ()
                        ),
                policy.claimContext(),
                policy.claimLimitPerChunk(),
                policy.claimLimitTotal(),
                policy.requireClaim(),
                force,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
    }

    @Nonnull
    Policy resolve(@Nonnull OwnerPopulationOperation operation, boolean populationMayIncrease) {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        if (config == null) {
            config = TwGlobalConfig.defaultConfig();
        }
        TameworkRuntimeSettings runtime = TameworkRuntimeSettings.currentOrNull();
        int limit = runtime == null
                ? config.getPopulationLimitPerPlayerOwnedTotal()
                : runtime.populationLimitPerPlayerOwnedTotal();
        TwGlobalConfig.PerPlayerLimitScope configuredScope = runtime == null
                ? config.getPopulationPerPlayerLimitScope()
                : TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(
                        runtime.populationPerPlayerLimitScope()
                );
        OwnerPopulationLimitScope scope = configuredScope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                ? OwnerPopulationLimitScope.GLOBAL
                : OwnerPopulationLimitScope.PER_WORLD;
        ClaimProviderRequest providerRequest = runtime == null
                ? config.getSimpleClaimsProviderRequest()
                : runtime.simpleClaimsProviderRequest();
        boolean masterEnabled = runtime == null
                ? config.isSimpleClaimsEnabled()
                : runtime.simpleClaimsEnabled();
        int perChunk = runtime == null
                ? config.getSimpleClaimsBreedingLimitPerClaimChunk()
                : runtime.simpleClaimsLimitPerClaimChunk();
        int total = runtime == null
                ? config.getSimpleClaimsBreedingLimitPerClaimTotal()
                : runtime.simpleClaimsLimitPerClaimTotal();
        boolean breedingRequiresClaim = runtime == null
                ? config.isSimpleClaimsBreedingRequiresClaim()
                : runtime.simpleClaimsBreedingRequiresClaim();
        long revision = runtime == null
                ? fallbackRevision(limit, scope, providerRequest, masterEnabled, perChunk, total, breedingRequiresClaim)
                : runtime.revision();
        boolean breeding = operation == OwnerPopulationOperation.BREEDING;
        boolean active = activePopulationPolicy(
                populationMayIncrease,
                breeding,
                masterEnabled,
                providerRequest,
                perChunk,
                total,
                breedingRequiresClaim
        );
        ClaimPolicyContext context = active
                ? claimProviderRegistry.resolveRequest(providerRequest, revision)
                : claimProviderRegistry.resolveProvider(ClaimIntegrationProvider.OFF, revision);
        return new Policy(
                limit,
                scope,
                revision,
                active ? perChunk : 0,
                active ? total : 0,
                active && breeding && breedingRequiresClaim,
                context
        );
    }

    private static boolean activePopulationPolicy(boolean populationMayIncrease,
                                                  boolean breeding,
                                                  boolean masterEnabled,
                                                  ClaimProviderRequest providerRequest,
                                                  int perChunk,
                                                  int total,
                                                  boolean breedingRequiresClaim) {
        ClaimIntegrationActivation activation = ClaimIntegrationActivation.evaluate(
                masterEnabled,
                providerRequest,
                perChunk,
                total,
                breedingRequiresClaim,
                false
        );
        boolean configured = perChunk > 0 || total > 0 || (breeding && breedingRequiresClaim);
        boolean invalidConfigured = masterEnabled && !providerRequest.valid() && configured;
        return populationMayIncrease
                && (activation.populationActive(breeding) || invalidConfigured);
    }

    private static long fallbackRevision(int limit,
                                         OwnerPopulationLimitScope scope,
                                         ClaimProviderRequest providerRequest,
                                         boolean masterEnabled,
                                         int perChunk,
                                         int total,
                                         boolean breedingRequiresClaim) {
        long mixed = 31L * Math.max(0, limit) + scope.ordinal();
        mixed = 31L * mixed + providerRequest.displayValue().hashCode();
        mixed = 31L * mixed + Boolean.hashCode(masterEnabled);
        mixed = 31L * mixed + Math.max(0, perChunk);
        mixed = 31L * mixed + Math.max(0, total);
        mixed = 31L * mixed + Boolean.hashCode(breedingRequiresClaim);
        return mixed & Long.MAX_VALUE;
    }

    private static boolean occupiesClaim(CompanionLifecycleState lifecycleState) {
        return lifecycleState == CompanionLifecycleState.ACTIVE
                || lifecycleState == CompanionLifecycleState.UNLOADED;
    }

    private static ClaimAdmissionOperation claimOperation(OwnerPopulationOperation operation) {
        return switch (operation) {
            case BREEDING -> ClaimAdmissionOperation.BREED;
            case RESTORE, LEGACY_ADOPTION -> ClaimAdmissionOperation.SPAWNER_RELEASE;
            case REHOME -> ClaimAdmissionOperation.TELEPORT;
            case NEW_OWNERSHIP, OWNER_TRANSFER, ADMIN_FORCE -> ClaimAdmissionOperation.SET_OWNER;
            case OWNER_CLEAR, LIFECYCLE_CHANGE -> ClaimAdmissionOperation.EXTERNAL;
        };
    }

    private static long incrementRevision(long revision) {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Claim occupancy revision exhausted.");
        }
        return revision + 1L;
    }

    record Policy(int limit,
                  @Nonnull OwnerPopulationLimitScope scope,
                  long settingsRevision,
                  int claimLimitPerChunk,
                  int claimLimitTotal,
                  boolean requireClaim,
                  @Nonnull ClaimPolicyContext claimContext) {
    }
}
