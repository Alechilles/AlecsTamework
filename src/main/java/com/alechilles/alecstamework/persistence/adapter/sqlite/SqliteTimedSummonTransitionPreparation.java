package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;

/** Exact alias/snapshot/lease/roster preparation plus lifecycle operation fence. */
final class SqliteTimedSummonTransitionPreparation
        implements PreparedOperationDetail {
    private final TimedSummonTransitionRequest request;

    SqliteTimedSummonTransitionPreparation(
            TimedSummonTransitionRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Timed transition preparation is required"
            );
        }
        this.request = request;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle source = requireSource(transaction);
        if (request.starting()) {
            requireApplied(
                    transaction.identities().leaseAlias(
                            request.beforeLease().profileId(),
                            request.liveAlias(),
                            operation.operationId(),
                            request.requestedAtMs()
                    ),
                    "timed_summon_alias_lease"
            );
        }
        CompanionLifecycle fenced = new CompanionLifecycle(
                source.profileId(),
                source.ownerId(),
                source.state(),
                source.location(),
                source.revision().next(),
                operation.operationId(),
                request.requestedAtMs(),
                source.lastReconciledGeneration(),
                source.quarantineIncidentId(),
                source.ownerWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                source.revision(), null, fenced
                        )
                ),
                "timed_summon_lifecycle_fence"
        );
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            return completedMatches(transaction);
        }
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.beforeLease().profileId())
                .orElse(null);
        if (lifecycle == null || !fencedMatches(lifecycle, operation)
                || !exactMembership(transaction)
                || !transaction.timedSummons()
                .find(request.beforeLease().profileId())
                .filter(request.beforeLease()::equals)
                .isPresent()) {
            return false;
        }
        return request.starting()
                ? startPreparedMatches(transaction, operation)
                : storePreparedMatches(transaction);
    }

    private CompanionLifecycle requireSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.beforeLease().profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "timed_summon_lifecycle_missing"
                ));
        if (!lifecycle.equals(request.groupAdmission().before())
                || lifecycle.activeOperationId() != null
                || lifecycle.quarantined()
                || !exactMembership(transaction)
                || !transaction.timedSummons()
                .find(request.beforeLease().profileId())
                .filter(request.beforeLease()::equals)
                .isPresent()) {
            throw new IllegalStateException(
                    "timed_summon_transition_source_mismatch"
            );
        }
        if (request.starting()) {
            requireStartSnapshot(transaction);
            if (transaction.identities().findCurrentAlias(
                    lifecycle.profileId()
            ).isPresent()) {
                throw new IllegalStateException(
                        "timed_summon_stored_alias_conflict"
                );
            }
        } else {
            requireCurrentAlias(transaction);
            if (transaction.snapshots()
                    .findById(request.snapshot().snapshotId())
                    .isPresent()) {
                throw new IllegalStateException(
                        "timed_summon_target_snapshot_exists"
                );
            }
        }
        return lifecycle;
    }

    private boolean fencedMatches(
            CompanionLifecycle lifecycle,
            OperationEnvelope operation
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        return lifecycle.profileId().equals(source.profileId())
                && java.util.Objects.equals(
                lifecycle.ownerId(), source.ownerId()
        )
                && lifecycle.state() == source.state()
                && lifecycle.location().equals(source.location())
                && lifecycle.revision().equals(source.revision().next())
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                && lifecycle.stateChangedAtMs() == request.requestedAtMs()
                && lifecycle.lastReconciledGeneration().equals(
                source.lastReconciledGeneration()
        )
                && java.util.Objects.equals(
                lifecycle.ownerWorldKey(), source.ownerWorldKey()
        )
                && !lifecycle.quarantined();
    }

    private boolean startPreparedMatches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.liveAlias()).orElse(null);
        try {
            requireStartSnapshot(transaction);
            return alias != null
                    && alias.profileId().equals(
                    request.beforeLease().profileId()
            )
                    && alias.state() == CompanionAlias.State.LEASED
                    && operation.operationId().equals(
                    alias.leaseOperationId()
            );
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private boolean storePreparedMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        try {
            requireCurrentAlias(transaction);
            return transaction.snapshots()
                    .findById(request.snapshot().snapshotId()).isEmpty();
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private boolean completedMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle finalLifecycle = request.finalLifecycle();
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.liveAlias()).orElse(null);
        if (!transaction.lifecycles()
                .findByProfile(request.beforeLease().profileId())
                .filter(finalLifecycle::equals).isPresent()
                || !transaction.timedSummons()
                .find(request.afterLease().profileId())
                .filter(request.afterLease()::equals).isPresent()
                || alias == null) {
            return false;
        }
        return request.starting()
                ? alias.state() == CompanionAlias.State.CURRENT
                && transaction.snapshots()
                .findById(request.snapshot().snapshotId())
                .filter(snapshot -> !snapshot.current()).isPresent()
                : alias.state() == CompanionAlias.State.RETIRED
                && transaction.snapshots()
                .findById(request.snapshot().snapshotId())
                .filter(request.snapshot()::equals).isPresent();
    }

    private boolean exactMembership(
            SqlitePersistenceTransactionContext transaction
    ) {
        try {
            SqliteCommandRosterEvidence.requireExact(
                    transaction,
                    request.beforeLease().profileId(),
                    request.familyKey(),
                    request.slotId(),
                    request.expectedMembershipRevision()
            );
            return true;
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private void requireStartSnapshot(
            SqlitePersistenceTransactionContext transaction
    ) {
        if (!transaction.snapshots()
                .findCurrent(
                        request.snapshot().profileId(),
                        request.snapshot().kind()
                )
                .filter(request.snapshot()::equals)
                .isPresent()) {
            throw new IllegalStateException(
                    "timed_summon_source_snapshot_mismatch"
            );
        }
    }

    private void requireCurrentAlias(
            SqlitePersistenceTransactionContext transaction
    ) {
        if (!transaction.identities()
                .findCurrentAlias(request.beforeLease().profileId())
                .filter(alias -> alias.alias().equals(request.liveAlias()))
                .isPresent()) {
            throw new IllegalStateException(
                    "timed_summon_current_alias_mismatch"
            );
        }
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

