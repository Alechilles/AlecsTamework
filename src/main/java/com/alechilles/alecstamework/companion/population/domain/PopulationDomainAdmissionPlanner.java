package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Derives only positive weighted domain deltas from one exact lifecycle transition. */
public final class PopulationDomainAdmissionPlanner {
    private PopulationDomainAdmissionPlanner() {
    }

    /**
     * Creates one reservation per positive named-domain delta. The target owner
     * owns the new claim; a clear or a release creates no positive reservation.
     */
    @Nonnull
    public static List<PopulationDomainReservation> plan(
            @Nonnull OperationId operationId,
            @Nonnull ProfileId profileId,
            @Nullable LifecycleRevision expectedLifecycleRevision,
            @Nullable OwnerId expectedOwner,
            @Nullable OwnerId targetOwner,
            @Nullable LifecycleState beforeState,
            @Nonnull LifecycleState afterState,
            @Nonnull String targetWorldKey,
            @Nonnull Collection<DomainPolicy> policies,
            long providerSnapshotRevision,
            long managedConfigRevision,
            long createdAtMs
    ) {
        return plan(
                operationId,
                profileId,
                expectedLifecycleRevision,
                expectedOwner,
                targetWorldKey,
                targetOwner,
                beforeState,
                afterState,
                targetWorldKey,
                policies,
                providerSnapshotRevision,
                managedConfigRevision,
                createdAtMs
        );
    }

    /**
     * Calculates target-bucket deltas from exact source and target ownership.
     * A per-world move reserves the destination bucket even when the owner is unchanged.
     */
    @Nonnull
    public static List<PopulationDomainReservation> plan(
            @Nonnull OperationId operationId,
            @Nonnull ProfileId profileId,
            @Nullable LifecycleRevision expectedLifecycleRevision,
            @Nullable OwnerId expectedOwner,
            @Nullable String sourceWorldKey,
            @Nullable OwnerId targetOwner,
            @Nullable LifecycleState beforeState,
            @Nonnull LifecycleState afterState,
            @Nonnull String targetWorldKey,
            @Nonnull Collection<DomainPolicy> policies,
            long providerSnapshotRevision,
            long managedConfigRevision,
            long createdAtMs
    ) {
        require(operationId, "Operation ID");
        require(profileId, "Profile ID");
        require(afterState, "Target lifecycle state");
        require(policies, "Domain policies");
        if (targetOwner == null) {
            return List.of();
        }
        PopulationDomainLifecycleClassifier.Classification before =
                beforeState == null
                        ? new PopulationDomainLifecycleClassifier.Classification(false, false)
                        : PopulationDomainLifecycleClassifier.classify(beforeState);
        PopulationDomainLifecycleClassifier.Classification after =
                PopulationDomainLifecycleClassifier.classify(afterState);
        boolean ownerChanged = !java.util.Objects.equals(expectedOwner, targetOwner);
        ArrayList<PopulationDomainReservation> result = new ArrayList<>();
        for (DomainPolicy policy : policies) {
            PopulationDomainBucket targetBucket = targetOwner == null
                    ? null
                    : bucket(targetOwner, targetWorldKey, policy);
            PopulationDomainBucket sourceBucket = expectedOwner == null
                    ? null
                    : bucket(expectedOwner, sourceWorldKey, policy);
            boolean sourceOwned = beforeState != null
                    && before.owned() && sourceBucket != null;
            boolean sourceDeployable = beforeState != null
                    && before.deployable() && sourceBucket != null;
            boolean targetOwned = policy.owned() && after.owned() && targetBucket != null;
            boolean targetDeployable = policy.deployable()
                    && after.deployable() && targetBucket != null;
            int ownedDelta = targetOwned
                    && (!sourceOwned || !sourceBucket.equals(targetBucket))
                    ? 1 : 0;
            int deployableDelta = targetDeployable
                    && (!sourceDeployable || !sourceBucket.equals(targetBucket))
                    ? 1 : 0;
            if (ownedDelta == 0 && deployableDelta == 0) {
                continue;
            }
            result.add(new PopulationDomainReservation(
                    operationId,
                    profileId,
                    expectedLifecycleRevision,
                    targetBucket,
                    ownedDelta,
                    deployableDelta,
                    policy.weight(),
                    policy.maxOwned(),
                    policy.maxDeployable(),
                    providerSnapshotRevision,
                    managedConfigRevision,
                    policy.policyRevision(),
                    createdAtMs
            ));
        }
        result.sort(Comparator.comparing(PopulationDomainReservation::bucket));
        return List.copyOf(result);
    }

    private static PopulationDomainBucket bucket(
            OwnerId owner,
            String worldKey,
            DomainPolicy policy
    ) {
        return new PopulationDomainBucket(
                owner,
                policy.domainId(),
                policy.scope(),
                policy.scope() == PopulationDomainScope.PER_WORLD
                        ? requireWorld(worldKey)
                        : null
        );
    }

    private static String requireWorld(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Per-world domain admission requires an exact world key"
            );
        }
        return worldKey.trim();
    }

    /** Creates a policy from one public provider claim and frozen limits. */
    @Nonnull
    public static DomainPolicy policy(
            @Nonnull PopulationDomainClaim claim,
            @Nonnull PopulationDomainScope scope,
            int maxOwned,
            int maxDeployable,
            long policyRevision
    ) {
        require(claim, "Domain claim");
        return new DomainPolicy(
                claim.domainId(),
                scope,
                claim.owned(),
                claim.deployable(),
                claim.weight(),
                maxOwned,
                maxDeployable,
                policyRevision
        );
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    /** Frozen policy evidence used to derive one reservation set. */
    public record DomainPolicy(
            @Nonnull String domainId,
            @Nonnull PopulationDomainScope scope,
            boolean owned,
            boolean deployable,
            int weight,
            int maxOwned,
            int maxDeployable,
            long policyRevision
    ) {
        public DomainPolicy {
            if (domainId == null || domainId.isBlank() || scope == null
                    || weight <= 0 || maxOwned < 0 || maxDeployable < 0
                    || policyRevision < 0 || (!owned && !deployable)) {
                throw new IllegalArgumentException("Valid domain policy is required");
            }
            domainId = domainId.trim();
        }
    }
}
