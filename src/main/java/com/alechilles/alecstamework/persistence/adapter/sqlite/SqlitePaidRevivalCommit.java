package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChange;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeCodec;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.revival.PaidRevivalEventCodec;
import com.alechilles.alecstamework.companion.revival.PaidRevivalOutcome;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.ArrayList;
import java.util.List;

/** Atomic canonical commit after exact paid-revival charge and spawn receipts. */
final class SqlitePaidRevivalCommit {
    List<ProjectionEventDraft> execute(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            PaidRevivalRequest request,
            long committedAtMs
    ) {
        CompanionLifecycle fenced = requireFenced(
                transaction, operation, request
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, fenced.profileId()
                );
        requireApplied(
                transaction.identities().promoteAlias(
                        request.targetAlias(),
                        operation.operationId(),
                        committedAtMs
                ),
                "paid_revival_alias_promotion"
        );
        requireApplied(
                transaction.snapshots().retireCurrent(
                        request.sourceSnapshot().snapshotId()
                ),
                "paid_revival_snapshot_retirement"
        );
        CompanionLifecycle active = request.finalLifecycle();
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                fenced.revision(),
                                operation.operationId(),
                                active
                        )
                ),
                "paid_revival_lifecycle"
        );
        TimedSummonLeaseChange timed = replaceTimed(
                transaction, request
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, fenced.profileId()
                );
        return events(
                transaction,
                operation,
                request,
                fenced,
                active,
                before,
                after,
                timed,
                committedAtMs
        );
    }

    private CompanionLifecycle requireFenced(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            PaidRevivalRequest request
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(source.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "paid_revival_lifecycle_missing"
                ));
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.targetAlias()).orElse(null);
        if (!SqlitePaidRevivalSourceEvidence.fencedMatches(
                lifecycle, request, operation
        )
                || !SqlitePaidRevivalSourceEvidence.leaseMatches(
                        alias, request, operation
                )
                || !stableSources(transaction, request)) {
            throw new IllegalStateException(
                    "paid_revival_commit_fence_mismatch"
            );
        }
        return lifecycle;
    }

    private boolean stableSources(
            SqlitePersistenceTransactionContext transaction,
            PaidRevivalRequest request
    ) {
        CompanionIdentity profile = transaction.identities()
                .findProfile(request.sourceSnapshot().profileId())
                .orElse(null);
        if (profile == null || profile.metadataRevision()
                != request.expectedProfileRevision()
                || transaction.snapshots()
                .findById(request.sourceSnapshot().snapshotId())
                .filter(request.sourceSnapshot()::equals)
                .filter(snapshot -> transaction.snapshots()
                        .findCurrent(
                                snapshot.profileId(), snapshot.kind()
                        )
                        .filter(snapshot::equals).isPresent())
                .isEmpty()) {
            return false;
        }
        try {
            SqliteCommandRosterEvidence.requireExact(
                    transaction,
                    request.sourceSnapshot().profileId(),
                    request.familyKey(),
                    request.slotId(),
                    request.expectedMembershipRevision()
            );
        } catch (IllegalStateException invalid) {
            return false;
        }
        TimedSummonActivation timed = request.timedActivation();
        return timed == null || transaction.timedSummons()
                .find(request.sourceSnapshot().profileId())
                .map(actual -> actual.equals(
                        timed.expectedPreviousLease()
                ))
                .orElse(timed.expectedPreviousLease() == null);
    }

    private TimedSummonLeaseChange replaceTimed(
            SqlitePersistenceTransactionContext transaction,
            PaidRevivalRequest request
    ) {
        TimedSummonActivation timed = request.timedActivation();
        if (timed == null) {
            return null;
        }
        Long expectedRevision = timed.expectedPreviousLease() == null
                ? null
                : timed.expectedPreviousLease().leaseRevision();
        return requireApplied(
                transaction.timedSummons().replace(
                        expectedRevision, timed.lease()
                ),
                "paid_revival_timed_lease"
        );
    }

    private List<ProjectionEventDraft> events(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            PaidRevivalRequest request,
            CompanionLifecycle fenced,
            CompanionLifecycle active,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            TimedSummonLeaseChange timed,
            long committedAtMs
    ) {
        ArrayList<ProjectionEventDraft> result = new ArrayList<>();
        result.add(PaidRevivalEventCodec.draft(
                operation.operationId(),
                new PaidRevivalOutcome(
                        request.callerNamespace(),
                        request.callerIdempotencyKey(),
                        request.familyKey().ownerId(),
                        request.familyKey().familyId(),
                        request.slotId(),
                        active.profileId(),
                        request.sourceSnapshot().snapshotId(),
                        request.targetAlias(),
                        request.targetWorldKey(),
                        active.revision(),
                        request.configId(),
                        request.configRevision(),
                        request.exactCost(),
                        request.chargeReceiptKey(),
                        request.spawnReceiptKey(),
                        request.timedActivation() == null
                                ? null
                                : request.timedActivation()
                                .lease().sessionId(),
                        committedAtMs
                )
        ));
        SqliteProvisionedCompanionLifecycleEvents.revival(
                transaction,
                operation.operationId(),
                active,
                request.groupAdmission().before().revision(),
                request.targetAlias(),
                committedAtMs
        ).ifPresent(result::add);
        result.add(SqliteCompanionProfileProjectionComposer.event(
                operation.operationId(),
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        active.profileId(),
                        active.revision().value(),
                        before,
                        after,
                        committedAtMs
                )
        ));
        result.add(CompanionLifecycleProjectionChangeCodec.draft(
                operation.operationId(),
                fenced,
                active,
                committedAtMs
        ));
        if (timed != null) {
            result.add(TimedSummonLeaseChangeCodec.draft(
                    operation.operationId(),
                    SqliteCommandSemanticEventEvidence.timed(
                            transaction,
                            timed,
                            timed.before() == null ? null : fenced,
                            active,
                            TimedSummonLeaseChangeEvidence.Reason
                                    .PAID_REVIVED
                    ),
                    committedAtMs
            ));
        }
        return List.copyOf(result);
    }

    private static <T> T requireApplied(
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
