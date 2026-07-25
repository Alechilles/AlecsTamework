package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureCommandAccessEvidence;
import com.alechilles.alecstamework.companion.capture.CapturePopulationGroupEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameLiveEvidence;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlanner;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.CommandActivationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.OwnerPopulationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.PopulationGroupEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.TargetEvidence;

/**
 * Authors exact engine-neutral evidence for an in-place tame-and-command-link capture.
 *
 * <p>The caller supplies one immutable snapshot of every participating authority. This author
 * performs an early fail-closed capacity and duplicate check; the durable operation repeats all
 * canonical checks transactionally before accepting the operation.</p>
 */
public final class SpawnerTameAndLinkEvidenceAuthor {
    private final SpawnerTameAndLinkCapacityValidator capacity =
            new SpawnerTameAndLinkCapacityValidator();

    /** Builds one internally consistent durable target from exact authoritative source evidence. */
    @Nonnull
    public CaptureTameAndLinkEvidence author(
            @Nonnull SpawnerTameAndLinkEvidenceInput input
    ) {
        if (input == null) {
            throw failure("capture_tame_input_missing");
        }
        requireCurrentProfile(input);
        CompanionIdentity targetIdentity = targetIdentity(input);
        CompanionLifecycle finalLifecycle = finalLifecycle(input);
        OwnerPopulationAdmissionPlan ownerPopulation =
                ownerPopulation(input);
        CapturePopulationGroupEvidence populationGroups =
                populationGroups(input, targetIdentity, finalLifecycle);
        TimedSummonActivation activation = commandActivation(input);
        return new CaptureTameAndLinkEvidence(
                input.currentIdentity(),
                targetIdentity,
                input.currentLifecycle(),
                finalLifecycle,
                ownerPopulation,
                populationGroups,
                command(input).expectedRosterRevision(),
                commandMembership(input),
                activation,
                liveEvidence(input)
        );
    }

    private void requireCurrentProfile(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        CompanionIdentity identity = input.currentIdentity();
        CompanionLifecycle lifecycle = input.currentLifecycle();
        if (!identity.profileId().equals(lifecycle.profileId())
                || lifecycle.state() != LifecycleState.ACTIVE
                || lifecycle.location().kind()
                != LifecycleLocationKind.LIVE_ENTITY
                || lifecycle.ownerId() != null
                || lifecycle.ownerWorldKey() != null
                || lifecycle.activeOperationId() != null
                || lifecycle.quarantined()
                || identity.roleId() == null) {
            throw failure("capture_tame_profile_not_exact_wild_live");
        }
        PopulationGroupAssignment assignment =
                groups(input).currentAssignment();
        if (assignment != null && (!assignment.profileId().equals(
                identity.profileId())
                || !java.util.Objects.equals(
                assignment.roleId(), identity.roleId())
                || assignment.sourceMetadataRevision()
                != identity.metadataRevision()
                || !assignment.sourceLifecycleRevision().equals(
                lifecycle.revision()))) {
            throw failure("capture_tame_group_assignment_stale");
        }
    }

    private CompanionIdentity targetIdentity(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        CompanionIdentity current = input.currentIdentity();
        TargetEvidence target = target(input);
        return new CompanionIdentity(
                current.profileId(),
                current.displayName(),
                target.roleId(),
                target.metadataJson(),
                Sha256Hash.ofUtf8(target.metadataJson()),
                input.currentLifecycle().location().worldKey(),
                current.createdAtMs(),
                input.requestedAtMs(),
                input.requestedAtMs(),
                Math.addExact(current.metadataRevision(), 1)
        );
    }

    private CompanionLifecycle finalLifecycle(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        CompanionLifecycle current = input.currentLifecycle();
        return new CompanionLifecycle(
                current.profileId(),
                target(input).ownerId(),
                LifecycleState.ACTIVE,
                current.location(),
                current.revision().next().next(),
                null,
                input.requestedAtMs(),
                current.lastReconciledGeneration(),
                null,
                current.location().worldKey()
        );
    }

    private OwnerPopulationAdmissionPlan ownerPopulation(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        CompanionLifecycle current = input.currentLifecycle();
        OwnerPopulationEvidence evidence = ownerPopulationEvidence(input);
        OwnerPopulationTransitionRequest transition =
                new OwnerPopulationTransitionRequest(
                        current.profileId(),
                        current.revision(),
                        null,
                        null,
                        target(input).ownerId(),
                        current.location().worldKey(),
                        evidence.globalLimit(),
                        evidence.perWorldLimit(),
                        input.requestedAtMs()
                );
        OwnerPopulationAdmissionPlan plan =
                OwnerPopulationAdmissionPlanner.plan(transition)
                        .orElseThrow(() -> failure(
                                "capture_tame_owner_plan_missing"
                        ));
        capacity.requireOwnerCapacity(plan, evidence.counts());
        return plan;
    }

    private CapturePopulationGroupEvidence populationGroups(
            SpawnerTameAndLinkEvidenceInput input,
            CompanionIdentity targetIdentity,
            CompanionLifecycle finalLifecycle
    ) {
        PopulationGroupEvidence evidence = groups(input);
        PopulationGroupAssignmentRequest request =
                new PopulationGroupAssignmentRequest(
                        targetIdentity.profileId(),
                        targetIdentity.metadataRevision(),
                        targetIdentity.roleId(),
                        finalLifecycle.revision(),
                        finalLifecycle.ownerId(),
                        finalLifecycle.ownerWorldKey(),
                        evidence.currentAssignment() == null
                                ? null
                                : evidence.currentAssignment()
                                .assignmentRevision(),
                        evidence.policyRevision(),
                        evidence.policies(),
                        input.requestedAtMs()
                );
        PopulationGroupAssignmentPlan classified =
                PopulationGroupAssignmentPlanner.plan(
                        input.operationId(),
                        request,
                        evidence.currentAssignment(),
                        finalLifecycle
                );
        List<PopulationGroupReservation> reservations =
                groupReservations(input, classified.target());
        capacity.requireGroupCapacity(reservations, evidence.counts());
        return new CapturePopulationGroupEvidence(
                evidence.currentAssignment(),
                new PopulationGroupAssignmentPlan(
                        classified.target(), reservations
                )
        );
    }

    private List<PopulationGroupReservation> groupReservations(
            SpawnerTameAndLinkEvidenceInput input,
            PopulationGroupAssignment target
    ) {
        CompanionLifecycle before = input.currentLifecycle();
        CompanionLifecycle admissionTarget = new CompanionLifecycle(
                before.profileId(),
                target(input).ownerId(),
                LifecycleState.ACTIVE,
                before.location(),
                before.revision().next(),
                null,
                input.requestedAtMs(),
                before.lastReconciledGeneration(),
                null,
                before.location().worldKey()
        );
        return PopulationGroupTransitionAdmissionPlanner.plan(
                input.operationId(),
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        admissionTarget,
                        target.assignmentRevision(),
                        groups(input).policyRevision(),
                        groups(input).policies(),
                        input.requestedAtMs()
                ),
                target
        );
    }

    private TimedSummonActivation commandActivation(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        CommandActivationEvidence command = command(input);
        requireCommandSource(input, command);
        TimedSummonPolicy policy = command.timedPolicy();
        TimedSummonLease lease = new TimedSummonLease(
                input.currentIdentity().profileId(),
                1,
                new TimedSummonSessionId(input.operationId().value()),
                policy.unlimited() ? null : policy.activeDurationMs(),
                null,
                policy,
                Set.of(),
                input.requestedAtMs(),
                input.requestedAtMs(),
                input.requestedAtMs()
        );
        return new TimedSummonActivation(
                command.familyKey(),
                command.slotId(),
                1,
                lease
        );
    }

    private void requireCommandSource(
            SpawnerTameAndLinkEvidenceInput input,
            CommandActivationEvidence command
    ) {
        CommandRosterMembershipDraft membership = commandMembership(input);
        CaptureCommandAccessEvidence access =
                target(input).commandAccess();
        if (!membership.profileId().equals(
                input.currentIdentity().profileId())
                || !membership.familyKey().ownerId().equals(
                target(input).ownerId())
                || !membership.familyKey().familyId().equals(
                access.commandFamilyId())
                || membership.changedAtMs() != input.requestedAtMs()) {
            throw failure("capture_tame_command_target_inconsistent");
        }
        requireRosterRevision(command);
        if (command.existingProfileMembership() != null
                || command.existingSlotMembership() != null
                || command.existingTimedLease() != null
                || rosterContainsDuplicate(command.currentRoster(),
                membership)) {
            throw failure("capture_tame_command_source_duplicate");
        }
    }

    private void requireRosterRevision(CommandActivationEvidence command) {
        CommandRoster roster = command.currentRoster();
        if (roster == null) {
            if (command.expectedRosterRevision() != 0) {
                throw failure("capture_tame_roster_revision_stale");
            }
            return;
        }
        if (!roster.familyKey().equals(
                command.familyKey())
                || roster.rosterRevision()
                != command.expectedRosterRevision()) {
            throw failure("capture_tame_roster_revision_stale");
        }
    }

    private boolean rosterContainsDuplicate(
            @Nullable CommandRoster roster,
            CommandRosterMembershipDraft target
    ) {
        return roster != null && roster.memberships().stream()
                .anyMatch(member ->
                        member.profileId().equals(target.profileId())
                                || member.slotId().equals(target.slotId()));
    }

    private CaptureTameLiveEvidence liveEvidence(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        TargetEvidence target = target(input);
        return new CaptureTameLiveEvidence(
                input.currentIdentity().roleId(),
                null,
                false,
                target.expectedLiveStateHash(),
                target.roleId(),
                target.ownerId(),
                target.ownerName(),
                target.targetLiveStateHash(),
                target.commandAccess()
        );
    }

    private IllegalArgumentException failure(String detail) {
        return new IllegalArgumentException(detail);
    }

    private TargetEvidence target(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        return input.intentEvidence().target();
    }

    private OwnerPopulationEvidence ownerPopulationEvidence(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        return input.intentEvidence().ownerPopulation();
    }

    private PopulationGroupEvidence groups(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        return input.intentEvidence().groups();
    }

    private CommandActivationEvidence command(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        return input.intentEvidence().command();
    }

    private CommandRosterMembershipDraft commandMembership(
            SpawnerTameAndLinkEvidenceInput input
    ) {
        CommandActivationEvidence command = command(input);
        return new CommandRosterMembershipDraft(
                command.slotId(),
                command.familyKey(),
                input.currentIdentity().profileId(),
                command.groupId(),
                command.activeForBulkCommands(),
                command.home(),
                input.requestedAtMs()
        );
    }
}
