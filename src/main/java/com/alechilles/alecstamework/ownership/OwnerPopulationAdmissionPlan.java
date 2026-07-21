package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Complete immutable durability and revalidation context for one owner transition.
 */
public record OwnerPopulationAdmissionPlan(
        @Nonnull OwnerPopulationTransitionRequest transition,
        @Nonnull CompanionPopulationStateRecord baselineState,
        @Nullable UUID finalNpcUuid,
        @Nullable String finalPhysicalWorldName,
        @Nullable Integer finalPhysicalChunkX,
        @Nullable Integer finalPhysicalChunkZ,
        @Nullable String source,
        @Nonnull String oldStateJson,
        @Nonnull String newStateJson,
        @Nullable String targetContextJson,
        long settingsRevision,
        @Nonnull ClaimProviderGeneration providerGeneration,
        @Nullable PopulationGroupRoleContext populationGroupRoleContext
) {
    public OwnerPopulationAdmissionPlan {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(baselineState, "baselineState");
        Objects.requireNonNull(oldStateJson, "oldStateJson");
        Objects.requireNonNull(newStateJson, "newStateJson");
        providerGeneration = providerGeneration == null
                ? ClaimProviderGeneration.NONE
                : providerGeneration;
        if (!transition.profileId().equals(baselineState.profileId())) {
            throw new IllegalArgumentException("Transition and baseline profile IDs must match.");
        }
        validateRevisionAndOwner(transition, baselineState);
        boolean noPhysicalLocation = finalPhysicalWorldName == null
                && finalPhysicalChunkX == null
                && finalPhysicalChunkZ == null;
        boolean completePhysicalLocation = finalPhysicalWorldName != null
                && !finalPhysicalWorldName.isBlank()
                && finalPhysicalChunkX != null
                && finalPhysicalChunkZ != null;
        if (!noPhysicalLocation && !completePhysicalLocation) {
            throw new IllegalArgumentException("Final physical location must be entirely present or absent.");
        }
    }

    /**
     * Binary/source-compatible construction seam for paths that do not yet carry role evidence.
     * A production population-group runtime resolves an existing role from the canonical profile;
     * positive creation paths must use the canonical constructor and supply explicit target-role
     * evidence instead of relying on this overload.
     */
    public OwnerPopulationAdmissionPlan(
            @Nonnull OwnerPopulationTransitionRequest transition,
            @Nonnull CompanionPopulationStateRecord baselineState,
            @Nullable UUID finalNpcUuid,
            @Nullable String finalPhysicalWorldName,
            @Nullable Integer finalPhysicalChunkX,
            @Nullable Integer finalPhysicalChunkZ,
            @Nullable String source,
            @Nonnull String oldStateJson,
            @Nonnull String newStateJson,
            @Nullable String targetContextJson,
            long settingsRevision,
            @Nonnull ClaimProviderGeneration providerGeneration) {
        this(transition, baselineState, finalNpcUuid, finalPhysicalWorldName,
                finalPhysicalChunkX, finalPhysicalChunkZ, source, oldStateJson, newStateJson,
                targetContextJson, settingsRevision, providerGeneration, null);
    }

    private static void validateRevisionAndOwner(OwnerPopulationTransitionRequest transition,
                                                 CompanionPopulationStateRecord baseline) {
        if (transition.expectedRevision() == OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION) {
            if (baseline.revision() != 0L || baseline.ownerUuid() != null) {
                throw new IllegalArgumentException("New profiles require an unowned revision-zero baseline.");
            }
            return;
        }
        if (baseline.revision() != transition.expectedRevision()) {
            throw new IllegalArgumentException("Baseline revision does not match the transition.");
        }
        if (!Objects.equals(baseline.ownerUuid(), transition.expectedOwnerId())) {
            throw new IllegalArgumentException("Baseline owner does not match the transition.");
        }
    }
}
