package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Pure positive-delta planner for canonical lifecycle transitions. */
public final class PopulationGroupTransitionAdmissionPlanner {
    private PopulationGroupTransitionAdmissionPlanner() {
    }

    @Nonnull
    public static List<PopulationGroupReservation> plan(
            @Nonnull OperationId operationId,
            @Nonnull PopulationGroupTransitionAdmissionRequest request,
            @Nonnull PopulationGroupAssignment assignment
    ) {
        if (operationId == null || request == null || assignment == null
                || !request.before().profileId().equals(
                assignment.profileId()
        )
                || request.expectedAssignmentRevision()
                != assignment.assignmentRevision()
                || request.expectedPolicyRevision()
                != assignment.policyRevision()) {
            throw new IllegalArgumentException(
                    "Exact group transition assignment is required"
            );
        }
        Map<PopulationGroupMembership, PopulationGroupPolicy> policies =
                policies(request.policies());
        if (!new TreeSet<>(assignment.memberships()).equals(
                new TreeSet<>(policies.keySet())
        )) {
            throw new IllegalArgumentException(
                    "Transition policies must match complete assignment"
            );
        }
        ArrayList<PopulationGroupReservation> reservations =
                new ArrayList<>();
        for (PopulationGroupMembership membership
                : assignment.memberships()) {
            PopulationGroupPolicy policy = policies.get(membership);
            addPositive(
                    reservations,
                    operationId,
                    request,
                    membership,
                    policy
            );
        }
        return List.copyOf(reservations);
    }

    private static void addPositive(
            ArrayList<PopulationGroupReservation> reservations,
            OperationId operationId,
            PopulationGroupTransitionAdmissionRequest request,
            PopulationGroupMembership membership,
            PopulationGroupPolicy policy
    ) {
        CompanionLifecycle before = request.before();
        CompanionLifecycle after = request.after();
        PopulationGroupBucket target = bucket(after, membership);
        if (target == null) {
            return;
        }
        PopulationGroupBucket source = bucket(before, membership);
        int owned = consumesOwned(after)
                && (!target.equals(source) || !consumesOwned(before))
                ? 1 : 0;
        int active = consumesActive(after)
                && (!target.equals(source) || !consumesActive(before))
                ? 1 : 0;
        if (owned == 0 && active == 0) {
            return;
        }
        reservations.add(new PopulationGroupReservation(
                operationId,
                after.profileId(),
                before.revision(),
                target,
                owned,
                active,
                policy.maxOwnedPerOwner(),
                policy.maxActivePerOwner(),
                policy.policyRevision(),
                request.requestedAtMs()
        ));
    }

    private static PopulationGroupBucket bucket(
            CompanionLifecycle lifecycle,
            PopulationGroupMembership membership
    ) {
        if (lifecycle.ownerId() == null
                || !consumesOwned(lifecycle)) {
            return null;
        }
        return new PopulationGroupBucket(
                lifecycle.ownerId(),
                membership.groupId(),
                membership.scope(),
                membership.scope() == PopulationGroupScope.GLOBAL
                        ? null
                        : requireOwnerWorld(lifecycle)
        );
    }

    private static Map<PopulationGroupMembership, PopulationGroupPolicy>
    policies(List<PopulationGroupPolicy> values) {
        HashMap<PopulationGroupMembership, PopulationGroupPolicy> result =
                new HashMap<>();
        for (PopulationGroupPolicy policy : values) {
            PopulationGroupMembership membership =
                    new PopulationGroupMembership(
                            policy.groupId(), policy.scope()
                    );
            if (result.putIfAbsent(membership, policy) != null) {
                throw new IllegalArgumentException(
                        "Transition group policies must be unique"
                );
            }
        }
        return Map.copyOf(result);
    }

    private static boolean consumesOwned(
            CompanionLifecycle lifecycle
    ) {
        return PopulationGroupLifecycleClassifier.consumesOwned(
                lifecycle.state()
        );
    }

    private static boolean consumesActive(
            CompanionLifecycle lifecycle
    ) {
        return PopulationGroupLifecycleClassifier.consumesActive(
                lifecycle.state()
        );
    }

    private static String requireOwnerWorld(
            CompanionLifecycle lifecycle
    ) {
        if (lifecycle.ownerWorldKey() == null) {
            throw new IllegalArgumentException(
                    "Per-world transition requires canonical owner world"
            );
        }
        return lifecycle.ownerWorldKey();
    }
}

