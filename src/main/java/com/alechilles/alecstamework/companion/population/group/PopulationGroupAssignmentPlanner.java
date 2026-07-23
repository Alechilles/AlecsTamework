package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure assignment and positive-delta planner over exact canonical lifecycle evidence. */
public final class PopulationGroupAssignmentPlanner {
    private PopulationGroupAssignmentPlanner() {
    }

    @Nonnull
    public static PopulationGroupAssignmentPlan plan(
            @Nonnull OperationId operationId,
            @Nonnull PopulationGroupAssignmentRequest request,
            @Nullable PopulationGroupAssignment current,
            @Nonnull CompanionLifecycle lifecycle
    ) {
        if (operationId == null || request == null || lifecycle == null
                || !request.profileId().equals(lifecycle.profileId())) {
            throw new IllegalArgumentException(
                    "Consistent group assignment planning evidence is required"
            );
        }
        long nextRevision = current == null
                ? 1
                : Math.addExact(current.assignmentRevision(), 1);
        List<PopulationGroupMembership> targetMemberships =
                request.policies().stream()
                        .map(policy -> new PopulationGroupMembership(
                                policy.groupId(), policy.scope()
                        ))
                        .toList();
        PopulationGroupAssignment target =
                new PopulationGroupAssignment(
                        request.profileId(),
                        request.expectedRoleId(),
                        targetMemberships,
                        request.policyRevision(),
                        request.expectedMetadataRevision(),
                        request.expectedLifecycleRevision(),
                        nextRevision,
                        request.requestedAtMs()
                );
        HashSet<PopulationGroupMembership> existing =
                new HashSet<>(current == null
                        ? List.of()
                        : current.memberships());
        ArrayList<PopulationGroupReservation> reservations =
                new ArrayList<>();
        if (lifecycle.ownerId() != null
                && PopulationGroupLifecycleClassifier.consumesOwned(
                lifecycle.state()
        )) {
            for (PopulationGroupPolicy policy : request.policies()) {
                PopulationGroupMembership membership =
                        new PopulationGroupMembership(
                                policy.groupId(), policy.scope()
                        );
                if (existing.contains(membership)) {
                    continue;
                }
                PopulationGroupBucket bucket = new PopulationGroupBucket(
                        lifecycle.ownerId(),
                        policy.groupId(),
                        policy.scope(),
                        policy.scope() == PopulationGroupScope.GLOBAL
                                ? null
                                : requireOwnerWorld(lifecycle)
                );
                reservations.add(new PopulationGroupReservation(
                        operationId,
                        request.profileId(),
                        request.expectedLifecycleRevision(),
                        bucket,
                        1,
                        PopulationGroupLifecycleClassifier.consumesActive(
                                lifecycle.state()
                        ) ? 1 : 0,
                        policy.maxOwnedPerOwner(),
                        policy.maxActivePerOwner(),
                        policy.policyRevision(),
                        request.requestedAtMs()
                ));
            }
        }
        return new PopulationGroupAssignmentPlan(target, reservations);
    }

    private static String requireOwnerWorld(CompanionLifecycle lifecycle) {
        if (lifecycle.ownerWorldKey() == null) {
            throw new IllegalArgumentException(
                    "Per-world group assignment requires canonical owner world"
            );
        }
        return lifecycle.ownerWorldKey();
    }
}
