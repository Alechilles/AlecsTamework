package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;

/** Cross-authority durable target for one successful in-place tame/link capture. */
public record CaptureTameAndLinkEvidence(
        @Nonnull CompanionIdentity expectedIdentity,
        @Nonnull CompanionIdentity targetIdentity,
        @Nonnull CompanionLifecycle expectedLifecycle,
        @Nonnull CompanionLifecycle finalLifecycle,
        @Nonnull OwnerPopulationAdmissionPlan ownerPopulation,
        @Nonnull CapturePopulationGroupEvidence populationGroups,
        long expectedRosterRevision,
        @Nonnull CommandRosterMembershipDraft rosterMembership,
        @Nonnull TimedSummonActivation timedActivation,
        @Nonnull CaptureTameLiveEvidence live
) {
    public CaptureTameAndLinkEvidence {
        if (expectedIdentity == null || targetIdentity == null
                || expectedLifecycle == null || finalLifecycle == null
                || ownerPopulation == null || populationGroups == null
                || rosterMembership == null || timedActivation == null
                || live == null || expectedRosterRevision < 0) {
            throw new IllegalArgumentException(
                    "Complete tame/link capture evidence is required"
            );
        }
        requireProfileAndIdentity(
                expectedIdentity, targetIdentity,
                expectedLifecycle, live
        );
        requireLifecycle(expectedLifecycle, finalLifecycle, live);
        requireRosterAndLease(
                expectedIdentity, rosterMembership,
                timedActivation, live
        );
        requirePopulation(
                expectedIdentity, targetIdentity,
                expectedLifecycle, finalLifecycle,
                ownerPopulation, populationGroups
        );
    }

    private static void requireProfileAndIdentity(
            CompanionIdentity expectedIdentity,
            CompanionIdentity targetIdentity,
            CompanionLifecycle expectedLifecycle,
            CaptureTameLiveEvidence live
    ) {
        if (!expectedIdentity.profileId().equals(
                targetIdentity.profileId()
        )
                || !expectedIdentity.profileId().equals(
                expectedLifecycle.profileId()
        )
                || targetIdentity.metadataRevision()
                != Math.addExact(
                expectedIdentity.metadataRevision(), 1
        )
                || !sameRole(
                expectedIdentity.roleId(), live.expectedRoleId()
        )
                || !java.util.Objects.equals(
                targetIdentity.roleId(), live.targetRoleId()
        )) {
            throw new IllegalArgumentException(
                    "Tame/link profile identity evidence is inconsistent"
            );
        }
    }

    private static boolean sameRole(String left, String right) {
        return left != null && right != null
                && left.equalsIgnoreCase(right);
    }

    private static void requireLifecycle(
            CompanionLifecycle expectedLifecycle,
            CompanionLifecycle finalLifecycle,
            CaptureTameLiveEvidence live
    ) {
        if (expectedLifecycle.state() != LifecycleState.ACTIVE
                || expectedLifecycle.activeOperationId() != null
                || expectedLifecycle.quarantined()
                || expectedLifecycle.ownerId() != null
                || expectedLifecycle.ownerWorldKey() != null
                || live.expectedOwnerId() != null
                || live.expectedTamed()
                || !java.util.Objects.equals(
                expectedLifecycle.ownerId(), live.expectedOwnerId()
        )
                || finalLifecycle.state() != LifecycleState.ACTIVE
                || finalLifecycle.activeOperationId() != null
                || finalLifecycle.quarantined()
                || finalLifecycle.ownerId() == null
                || !finalLifecycle.location().equals(
                expectedLifecycle.location()
        )
                || !finalLifecycle.revision().equals(
                expectedLifecycle.revision().next().next()
                )
                || !finalLifecycle.ownerId().equals(live.targetOwnerId())
                || !finalLifecycle.lastReconciledGeneration().equals(
                expectedLifecycle.lastReconciledGeneration()
        )
                || !java.util.Objects.equals(
                finalLifecycle.ownerWorldKey(),
                expectedLifecycle.location().worldKey()
        )
                || !finalLifecycle.location().equals(
                LifecycleLocation.liveEntity(
                        expectedLifecycle.location().key(),
                        expectedLifecycle.location().worldKey()
                )
        )) {
            throw new IllegalArgumentException(
                    "Tame/link lifecycle evidence is inconsistent"
            );
        }
    }

    private static void requireRosterAndLease(
            CompanionIdentity expectedIdentity,
            CommandRosterMembershipDraft rosterMembership,
            TimedSummonActivation timedActivation,
            CaptureTameLiveEvidence live
    ) {
        if (!rosterMembership.profileId().equals(
                expectedIdentity.profileId()
        )
                || !rosterMembership.familyKey().ownerId().equals(
                live.targetOwnerId()
        )
                || !rosterMembership.familyKey().familyId().equals(
                live.commandAccess().commandFamilyId()
        )
                || !timedActivation.familyKey().equals(
                rosterMembership.familyKey()
        )
                || !timedActivation.slotId().equals(
                rosterMembership.slotId()
        )
                || timedActivation.expectedMembershipRevision() != 1
                || timedActivation.expectedPreviousLease() != null
                || !timedActivation.lease().profileId().equals(
                expectedIdentity.profileId()
        )
                || timedActivation.lease().leaseRevision() != 1
                || !timedActivation.lease().activeSession()) {
            throw new IllegalArgumentException(
                    "Tame/link roster and timed evidence is inconsistent"
            );
        }
    }

    private static void requirePopulation(
            CompanionIdentity expectedIdentity,
            CompanionIdentity targetIdentity,
            CompanionLifecycle expectedLifecycle,
            CompanionLifecycle finalLifecycle,
            OwnerPopulationAdmissionPlan ownerPopulation,
            CapturePopulationGroupEvidence populationGroups
    ) {
        if (!ownerPopulation.profileId().equals(
                expectedIdentity.profileId()
        )
                || !expectedLifecycle.revision().equals(
                ownerPopulation.expectedLifecycleRevision()
        )
                || !populationGroups.targetPlan().target()
                .profileId().equals(expectedIdentity.profileId())
                || !populationGroups.targetPlan().target().roleId()
                .equals(targetIdentity.roleId())
                || populationGroups.targetPlan().target()
                .sourceMetadataRevision()
                != targetIdentity.metadataRevision()
                || !populationGroups.targetPlan().target()
                .sourceLifecycleRevision()
                .equals(finalLifecycle.revision())
                || !ownerPopulationMatches(
                finalLifecycle, ownerPopulation
        )
                || !groupReservationsMatch(
                expectedLifecycle,
                finalLifecycle,
                populationGroups
        )) {
            throw new IllegalArgumentException(
                    "Tame/link population evidence is inconsistent"
            );
        }
    }

    private static boolean ownerPopulationMatches(
            CompanionLifecycle target,
            OwnerPopulationAdmissionPlan population
    ) {
        Set<OwnerPopulationScope> expected = Set.of(
                OwnerPopulationScope.global(target.ownerId()),
                OwnerPopulationScope.perWorld(
                        target.ownerId(), target.ownerWorldKey()
                )
        );
        return population.increases().size() == expected.size()
                && population.increases().stream().allMatch(increase ->
                increase.capacityDelta() == 1
                        && expected.contains(increase.scope())
        );
    }

    private static boolean groupReservationsMatch(
            CompanionLifecycle source,
            CompanionLifecycle target,
            CapturePopulationGroupEvidence groups
    ) {
        var assignment = groups.targetPlan().target();
        var reservations = groups.targetPlan().reservations();
        if (reservations.size() != assignment.memberships().size()) {
            return false;
        }
        HashSet<PopulationGroupMembership> matched = new HashSet<>();
        for (PopulationGroupReservation reservation : reservations) {
            PopulationGroupMembership membership =
                    matchingMembership(
                            assignment.memberships(), reservation
                    );
            if (membership == null
                    || !matched.add(membership)
                    || !source.revision().equals(
                    reservation.expectedLifecycleRevision()
            )
                    || !reservation.bucket().ownerId().equals(
                    target.ownerId()
            )
                    || reservation.ownedDelta() != 1
                    || reservation.activeDelta() != 1
                    || reservation.policyRevision()
                    != assignment.policyRevision()
                    || reservation.createdAtMs()
                    != assignment.assignedAtMs()) {
                return false;
            }
            String expectedWorld =
                    membership.scope() == PopulationGroupScope.PER_WORLD
                            ? target.ownerWorldKey()
                            : null;
            if (!java.util.Objects.equals(
                    expectedWorld,
                    reservation.bucket().ownerWorldKey()
            )) {
                return false;
            }
        }
        return matched.size() == assignment.memberships().size();
    }

    private static PopulationGroupMembership matchingMembership(
            java.util.List<PopulationGroupMembership> memberships,
            PopulationGroupReservation reservation
    ) {
        for (PopulationGroupMembership membership : memberships) {
            if (membership.groupId().equals(
                    reservation.bucket().groupId()
            ) && membership.scope() == reservation.bucket().scope()) {
                return membership;
            }
        }
        return null;
    }
}
