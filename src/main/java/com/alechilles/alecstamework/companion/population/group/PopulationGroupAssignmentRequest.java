package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact canonical source and winning policy snapshot for one complete classification. */
public record PopulationGroupAssignmentRequest(
        @Nonnull ProfileId profileId,
        long expectedMetadataRevision,
        @Nullable String expectedRoleId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nullable OwnerId expectedOwnerId,
        @Nullable String expectedOwnerWorldKey,
        @Nullable Long expectedAssignmentRevision,
        long policyRevision,
        @Nonnull List<PopulationGroupPolicy> policies,
        long requestedAtMs
) {
    public PopulationGroupAssignmentRequest {
        if (profileId == null || expectedMetadataRevision < 0
                || expectedLifecycleRevision == null
                || policyRevision < 0 || policies == null) {
            throw new IllegalArgumentException(
                    "Complete population group assignment request is required"
            );
        }
        expectedRoleId = normalize(expectedRoleId);
        expectedOwnerWorldKey = normalize(expectedOwnerWorldKey);
        if (expectedOwnerId == null && expectedOwnerWorldKey != null) {
            throw new IllegalArgumentException(
                    "Expected owner world requires an owner"
            );
        }
        if (expectedAssignmentRevision != null
                && expectedAssignmentRevision <= 0) {
            throw new IllegalArgumentException(
                    "Expected assignment revision must be positive"
            );
        }
        TreeSet<PopulationGroupPolicy> sorted =
                new TreeSet<>(policies);
        if (sorted.size() != policies.size()
                || sorted.stream().anyMatch(policy ->
                policy.policyRevision() != policyRevision)) {
            throw new IllegalArgumentException(
                    "Group policies must be unique and share the request revision"
            );
        }
        if (expectedRoleId == null && !sorted.isEmpty()) {
            throw new IllegalArgumentException(
                    "A profile without a role cannot have group policies"
            );
        }
        policies = List.copyOf(sorted);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
