package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Pure owned-capacity reservation planner for a not-yet-created profile. */
public final class PopulationGroupProvisioningAdmissionPlanner {
    private PopulationGroupProvisioningAdmissionPlanner() {
    }

    @Nonnull
    public static List<PopulationGroupReservation> plan(
            @Nonnull OperationId operationId,
            @Nonnull CompanionLifecycle lifecycle,
            @Nonnull PopulationGroupAssignment assignment,
            @Nonnull List<PopulationGroupPolicy> policies
    ) {
        if (operationId == null || lifecycle == null
                || assignment == null || policies == null
                || lifecycle.state()
                != LifecycleState.PROVISIONED_DORMANT
                || lifecycle.ownerId() == null
                || !lifecycle.profileId().equals(assignment.profileId())
                || !lifecycle.revision().equals(
                assignment.sourceLifecycleRevision()
        )
                || assignment.policyRevision() < 0) {
            throw new IllegalArgumentException(
                    "Exact provisioned group evidence is required"
            );
        }
        Map<PopulationGroupMembership, PopulationGroupPolicy> byGroup =
                policies(policies, assignment.policyRevision());
        if (!new TreeSet<>(assignment.memberships()).equals(
                new TreeSet<>(byGroup.keySet())
        )) {
            throw new IllegalArgumentException(
                    "Provisioning policies must match complete assignment"
            );
        }
        ArrayList<PopulationGroupReservation> result =
                new ArrayList<>();
        for (PopulationGroupMembership membership
                : assignment.memberships()) {
            PopulationGroupPolicy policy = byGroup.get(membership);
            result.add(new PopulationGroupReservation(
                    operationId,
                    lifecycle.profileId(),
                    null,
                    new PopulationGroupBucket(
                            lifecycle.ownerId(),
                            membership.groupId(),
                            membership.scope(),
                            membership.scope()
                                    == PopulationGroupScope.GLOBAL
                                    ? null
                                    : requireOwnerWorld(lifecycle)
                    ),
                    1,
                    0,
                    policy.maxOwnedPerOwner(),
                    policy.maxActivePerOwner(),
                    policy.policyRevision(),
                    lifecycle.stateChangedAtMs()
            ));
        }
        return List.copyOf(result);
    }

    private static Map<PopulationGroupMembership, PopulationGroupPolicy>
    policies(List<PopulationGroupPolicy> values, long policyRevision) {
        HashMap<PopulationGroupMembership, PopulationGroupPolicy> result =
                new HashMap<>();
        for (PopulationGroupPolicy policy : values) {
            if (policy == null
                    || policy.policyRevision() != policyRevision) {
                throw new IllegalArgumentException(
                        "Provisioning policy revision is inconsistent"
                );
            }
            PopulationGroupMembership membership =
                    new PopulationGroupMembership(
                            policy.groupId(), policy.scope()
                    );
            if (result.putIfAbsent(membership, policy) != null) {
                throw new IllegalArgumentException(
                        "Provisioning policies must be unique"
                );
            }
        }
        return Map.copyOf(result);
    }

    private static String requireOwnerWorld(
            CompanionLifecycle lifecycle
    ) {
        if (lifecycle.ownerWorldKey() == null) {
            throw new IllegalArgumentException(
                    "Per-world provisioning requires an owner world"
            );
        }
        return lifecycle.ownerWorldKey();
    }
}
