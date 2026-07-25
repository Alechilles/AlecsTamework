package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeCodec;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeEvidence;
import com.alechilles.alecstamework.companion.command.CommandRosterMutationOutcome;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChange;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeCodec;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentChange;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.ArrayList;
import java.util.List;

/** Atomic identity/lifecycle/group/roster/lease commit for in-place tame/link capture. */
final class SqliteCompanionCaptureTameCommit {
    List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CaptureTameAndLinkEvidence evidence,
            long committedAtMs
    ) {
        requireSources(transaction, operation, evidence);
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        evidence.expectedIdentity().profileId()
                );
        requireApplied(
                transaction.identities().updateProfile(
                        evidence.targetIdentity(),
                        evidence.expectedIdentity().metadataRevision()
                ),
                "capture_tame_identity"
        );
        CompanionLifecycle fenced = transaction.lifecycles()
                .findByProfile(evidence.expectedLifecycle().profileId())
                .orElseThrow();
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                fenced.revision(),
                                operation.operationId(),
                                evidence.finalLifecycle()
                        )
                ),
                "capture_tame_lifecycle"
        );
        PopulationGroupAssignment beforeGroups =
                evidence.populationGroups().expectedAssignment();
        PopulationGroupAssignment afterGroups =
                evidence.populationGroups().targetPlan().target();
        requireApplied(
                transaction.populationGroups().replaceAssignment(
                        beforeGroups == null
                                ? null
                                : beforeGroups.assignmentRevision(),
                        afterGroups
                ),
                "capture_tame_population_groups"
        );
        CommandRosterMutationOutcome roster = requireApplied(
                transaction.commandRosters().upsert(
                        evidence.expectedRosterRevision(),
                        null,
                        evidence.rosterMembership()
                ),
                "capture_tame_roster"
        );
        TimedSummonLeaseChange lease = requireApplied(
                transaction.timedSummons().replace(
                        null, evidence.timedActivation().lease()
                ),
                "capture_tame_lease"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        evidence.targetIdentity().profileId()
                );
        return events(
                transaction,
                operation,
                evidence,
                fenced,
                beforeGroups,
                afterGroups,
                roster,
                lease,
                before,
                after,
                committedAtMs
        );
    }

    static boolean matchesDurable(
            SqlitePersistenceTransactionContext transaction,
            CaptureTameAndLinkEvidence evidence
    ) {
        return transaction.identities()
                .findProfile(evidence.targetIdentity().profileId())
                .filter(evidence.targetIdentity()::equals)
                .isPresent()
                && transaction.lifecycles()
                .findByProfile(evidence.finalLifecycle().profileId())
                .filter(evidence.finalLifecycle()::equals)
                .isPresent()
                && transaction.populationGroups().findAssignment(
                evidence.targetIdentity().profileId()
        ).filter(
                evidence.populationGroups().targetPlan().target()::equals
        ).isPresent()
                && matchesCommandTarget(transaction, evidence);
    }

    static boolean matchesCommandTarget(
            SqlitePersistenceTransactionContext transaction,
            CaptureTameAndLinkEvidence evidence
    ) {
        return transaction.commandRosters().findByProfile(
                evidence.targetIdentity().profileId()
        ).filter(membership ->
                SqliteCompanionProvisioningPreparation.membershipMatches(
                        membership, evidence.rosterMembership()
                )
                        && membership.membershipRevision() == 1
        ).isPresent()
                && transaction.commandRosters().findRoster(
                evidence.rosterMembership().familyKey()
        ).filter(roster -> roster.rosterRevision()
                == evidence.expectedRosterRevision() + 1).isPresent()
                && transaction.timedSummons().find(
                evidence.targetIdentity().profileId()
        ).filter(evidence.timedActivation().lease()::equals).isPresent();
    }

    private void requireSources(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CaptureTameAndLinkEvidence evidence
    ) {
        CompanionIdentity identity = transaction.identities()
                .findProfile(evidence.expectedIdentity().profileId())
                .orElse(null);
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(evidence.expectedLifecycle().profileId())
                .orElse(null);
        PopulationGroupAssignment groups =
                transaction.populationGroups().findAssignment(
                        evidence.expectedIdentity().profileId()
                ).orElse(null);
        boolean fenced =
                SqliteCompanionCapturePreparation.matchesFence(
                        lifecycle,
                        operation,
                        evidence.expectedLifecycle(),
                        evidence.finalLifecycle().stateChangedAtMs()
                );
        if (!evidence.expectedIdentity().equals(identity)
                || !fenced
                || !java.util.Objects.equals(
                evidence.populationGroups().expectedAssignment(),
                groups
        )
                || transaction.commandRosters().findByProfile(
                evidence.expectedIdentity().profileId()
        ).isPresent()
                || transaction.commandRosters().findBySlot(
                evidence.rosterMembership().slotId()
        ).isPresent()
                || transaction.commandRosters().findRoster(
                evidence.rosterMembership().familyKey()
        ).map(roster -> roster.rosterRevision()
                != evidence.expectedRosterRevision()).orElse(
                evidence.expectedRosterRevision() != 0
        )
                || transaction.timedSummons().find(
                evidence.expectedIdentity().profileId()
        ).isPresent()) {
            throw new IllegalStateException(
                    "capture_tame_durable_source_mismatch"
            );
        }
    }

    private List<ProjectionEventDraft> events(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CaptureTameAndLinkEvidence evidence,
            CompanionLifecycle fenced,
            PopulationGroupAssignment beforeGroups,
            PopulationGroupAssignment afterGroups,
            CommandRosterMutationOutcome roster,
            TimedSummonLeaseChange lease,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            long committedAtMs
    ) {
        ArrayList<ProjectionEventDraft> events = new ArrayList<>();
        events.add(SqliteCompanionProfileProjectionComposer.event(
                operation.operationId(),
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.METADATA,
                        evidence.targetIdentity().profileId(),
                        evidence.targetIdentity().metadataRevision(),
                        before,
                        after,
                        committedAtMs
                )
        ));
        events.add(CompanionLifecycleProjectionChangeCodec.draft(
                operation.operationId(),
                fenced,
                evidence.finalLifecycle(),
                committedAtMs
        ));
        events.add(PopulationGroupAssignmentChangeCodec.draft(
                operation.operationId(),
                new PopulationGroupAssignmentChange(
                        evidence.targetIdentity().profileId(),
                        beforeGroups,
                        afterGroups
                )
        ));
        events.add(CommandRosterMembershipChangeCodec.draft(
                operation.operationId(),
                SqliteCommandSemanticEventEvidence.roster(
                        transaction,
                        roster,
                        evidence.finalLifecycle(),
                        CommandRosterMembershipChangeEvidence.Reason
                                .TAME_AND_LINKED
                ),
                committedAtMs
        ));
        events.add(TimedSummonLeaseChangeCodec.draft(
                operation.operationId(),
                SqliteCommandSemanticEventEvidence.timed(
                        transaction,
                        lease,
                        null,
                        evidence.finalLifecycle(),
                        TimedSummonLeaseChangeEvidence.Reason
                                .TAME_AND_LINKED
                )
        ));
        return List.copyOf(events);
    }

    private <T> T requireApplied(
            PersistenceMutationResult<T> result,
            String operation
    ) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    operation + "_" + (result == null
                            ? "null"
                            : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }
}
