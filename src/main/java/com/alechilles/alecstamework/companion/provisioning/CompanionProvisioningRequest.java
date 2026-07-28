package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact canonical evidence for one atomic dormant provisioning grant. */
public record CompanionProvisioningRequest(
        @Nonnull ProvisioningOrigin origin,
        @Nullable UUID correlationId,
        @Nonnull CompanionIdentity identity,
        @Nonnull CompanionLifecycle lifecycle,
        @Nonnull PopulationGroupAssignment groupAssignment,
        @Nonnull List<PopulationGroupPolicy> groupPolicies,
        int globalOwnerLimit,
        int perWorldOwnerLimit,
        @Nullable CommandRosterMembershipDraft commandMembership,
        @Nullable Long expectedCommandRosterRevision,
        long requestedAtMs
) {
    public CompanionProvisioningRequest {
        if (origin == null || identity == null || lifecycle == null
                || groupAssignment == null || groupPolicies == null
                || globalOwnerLimit < 0 || perWorldOwnerLimit < 0) {
            throw new IllegalArgumentException(
                    "Complete provisioning request is required"
            );
        }
        groupPolicies = sortedPolicies(groupPolicies);
        requireIdentity(origin, identity, lifecycle, requestedAtMs);
        requireLifecycle(origin, lifecycle, requestedAtMs);
        requireAssignment(
                identity, lifecycle, groupAssignment, groupPolicies,
                requestedAtMs
        );
        requireCommand(
                origin, lifecycle, commandMembership,
                expectedCommandRosterRevision, requestedAtMs
        );
    }

    private static void requireIdentity(
            ProvisioningOrigin origin,
            CompanionIdentity identity,
            CompanionLifecycle lifecycle,
            long requestedAtMs
    ) {
        if (!identity.profileId().equals(origin.profileId())
                || identity.roleId() == null
                || identity.metadataRevision() != 0
                || identity.createdAtMs() != requestedAtMs
                || identity.updatedAtMs() != requestedAtMs
                || identity.lastActiveAtMs() != requestedAtMs
                || !Objects.equals(
                        identity.lastKnownWorldKey(),
                        lifecycle.ownerWorldKey()
                )) {
            throw new IllegalArgumentException(
                    "Provisioning identity must be exact initial evidence"
            );
        }
    }

    private static void requireLifecycle(
            ProvisioningOrigin origin,
            CompanionLifecycle lifecycle,
            long requestedAtMs
    ) {
        if (!lifecycle.profileId().equals(origin.profileId())
                || lifecycle.ownerId() == null
                || lifecycle.ownerWorldKey() == null
                || lifecycle.state()
                != LifecycleState.PROVISIONED_DORMANT
                || !lifecycle.location().equals(LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        origin.stableKey()
                ))
                || !lifecycle.revision().equals(
                        LifecycleRevision.INITIAL
                )
                || lifecycle.activeOperationId() != null
                || lifecycle.quarantined()
                || lifecycle.stateChangedAtMs() != requestedAtMs
                || !lifecycle.lastReconciledGeneration().equals(
                        ReconciliationGeneration.INITIAL
                )) {
            throw new IllegalArgumentException(
                    "Provisioning lifecycle must be exact dormant evidence"
            );
        }
    }

    private static void requireAssignment(
            CompanionIdentity identity,
            CompanionLifecycle lifecycle,
            PopulationGroupAssignment assignment,
            List<PopulationGroupPolicy> policies,
            long requestedAtMs
    ) {
        TreeSet<PopulationGroupMembership> policyMemberships =
                new TreeSet<>();
        for (PopulationGroupPolicy policy : policies) {
            if (policy.policyRevision() != assignment.policyRevision()
                    || !policyMemberships.add(
                    new PopulationGroupMembership(
                            policy.groupId(), policy.scope()
                    )
            )) {
                throw new IllegalArgumentException(
                        "Provisioning group policies are inconsistent"
                );
            }
        }
        if (!assignment.profileId().equals(identity.profileId())
                || !Objects.equals(
                        assignment.roleId(), identity.roleId()
                )
                || assignment.sourceMetadataRevision() != 0
                || !assignment.sourceLifecycleRevision().equals(
                        lifecycle.revision()
                )
                || assignment.assignmentRevision() != 1
                || assignment.assignedAtMs() != requestedAtMs
                || !new TreeSet<>(assignment.memberships()).equals(
                        policyMemberships
                )) {
            throw new IllegalArgumentException(
                    "Provisioning group assignment must be exact"
            );
        }
    }

    private static void requireCommand(
            ProvisioningOrigin origin,
            CompanionLifecycle lifecycle,
            CommandRosterMembershipDraft membership,
            Long expectedRosterRevision,
            long requestedAtMs
    ) {
        if ((membership == null) != (expectedRosterRevision == null)
                || expectedRosterRevision != null
                && expectedRosterRevision < 0
                || membership != null
                && (!membership.profileId().equals(origin.profileId())
                || !membership.slotId().equals(origin.commandSlotId())
                || !membership.familyKey().ownerId().equals(
                        lifecycle.ownerId()
                )
                || membership.changedAtMs() != requestedAtMs)) {
            throw new IllegalArgumentException(
                    "Provisioning command membership must be exact"
            );
        }
    }

    private static List<PopulationGroupPolicy> sortedPolicies(
            List<PopulationGroupPolicy> policies
    ) {
        if (policies.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Provisioning group policies cannot contain null"
            );
        }
        TreeSet<PopulationGroupPolicy> sorted =
                new TreeSet<>(policies);
        if (sorted.size() != policies.size()) {
            throw new IllegalArgumentException(
                    "Provisioning group policies must be unique"
            );
        }
        return List.copyOf(sorted);
    }
}

