package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
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
                || !java.util.Objects.equals(
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

    private static void requireLifecycle(
            CompanionLifecycle expectedLifecycle,
            CompanionLifecycle finalLifecycle,
            CaptureTameLiveEvidence live
    ) {
        if (expectedLifecycle.state() != LifecycleState.ACTIVE
                || expectedLifecycle.activeOperationId() != null
                || expectedLifecycle.quarantined()
                || !java.util.Objects.equals(
                expectedLifecycle.ownerId(), live.expectedOwnerId()
        )
                || finalLifecycle.state() != LifecycleState.ACTIVE
                || finalLifecycle.activeOperationId() != null
                || finalLifecycle.quarantined()
                || !finalLifecycle.location().equals(
                expectedLifecycle.location()
        )
                || !finalLifecycle.revision().equals(
                expectedLifecycle.revision().next().next()
        )
                || !finalLifecycle.ownerId().equals(live.targetOwnerId())
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
                .equals(finalLifecycle.revision())) {
            throw new IllegalArgumentException(
                    "Tame/link population evidence is inconsistent"
            );
        }
    }
}
