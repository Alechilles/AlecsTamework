package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Exact group policy and lifecycle evidence for one canonical transition. */
public record PopulationGroupTransitionAdmissionRequest(
        @Nonnull CompanionLifecycle before,
        @Nonnull CompanionLifecycle after,
        long expectedAssignmentRevision,
        long expectedPolicyRevision,
        @Nonnull List<PopulationGroupPolicy> policies,
        long requestedAtMs
) {
    public PopulationGroupTransitionAdmissionRequest {
        if (before == null || after == null
                || expectedAssignmentRevision <= 0
                || expectedPolicyRevision < 0 || policies == null
                || !before.profileId().equals(after.profileId())
                || !after.revision().equals(before.revision().next())) {
            throw new IllegalArgumentException(
                    "Complete group transition admission evidence is required"
            );
        }
        TreeSet<PopulationGroupPolicy> sorted =
                new TreeSet<>(policies);
        if (sorted.size() != policies.size()
                || sorted.stream().anyMatch(policy ->
                policy.policyRevision() != expectedPolicyRevision)) {
            throw new IllegalArgumentException(
                    "Transition policies must be unique and revision-consistent"
            );
        }
        policies = List.copyOf(sorted);
    }
}
