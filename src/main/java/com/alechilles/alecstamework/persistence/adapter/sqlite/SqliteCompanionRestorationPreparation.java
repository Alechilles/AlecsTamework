package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;

/**
 * Atomic restoration fence: exact source, target alias lease, then lifecycle operation fence.
 */
final class SqliteCompanionRestorationPreparation
        implements PreparedOperationDetail {
    private final CompanionRestorationRequest restoration;

    SqliteCompanionRestorationPreparation(
            CompanionRestorationRequest restoration
    ) {
        if (restoration == null) {
            throw new IllegalArgumentException("Restoration preparation is required");
        }
        this.restoration = restoration;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle current = requireRestorableSource(
                transaction, restoration
        );
        requireApplied(
                transaction.identities().leaseAlias(
                        restoration.profileId(),
                        restoration.targetAlias(),
                        operation.operationId(),
                        restoration.requestedAtMs()
                ),
                "restoration_alias_lease"
        );
        CompanionLifecycle fenced = new CompanionLifecycle(
                current.profileId(),
                current.ownerId(),
                current.state(),
                current.location(),
                current.revision().next(),
                operation.operationId(),
                restoration.requestedAtMs(),
                current.lastReconciledGeneration(),
                current.quarantineIncidentId(),
                current.ownerWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        current.revision(), null, fenced
                )),
                "restoration_lifecycle_fence"
        );
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(restoration.profileId())
                .orElse(null);
        CompanionAlias alias = transaction.identities()
                .resolveAlias(restoration.targetAlias())
                .orElse(null);
        if (lifecycle == null || alias == null
                || !alias.profileId().equals(restoration.profileId())) {
            return false;
        }
        boolean fenced = lifecycle.revision().equals(
                restoration.expectedLifecycleRevision().next()
        ) && lifecycle.state() == restoration.sourceState()
                && lifecycle.location().equals(LifecycleLocation.none())
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        ) && alias.state() == CompanionAlias.State.LEASED
                && operation.operationId().equals(alias.leaseOperationId())
                && exactCurrentSnapshot(transaction);
        if (fenced) {
            return true;
        }
        return matchesCompleted(transaction, operation, lifecycle, alias);
    }

    private boolean matchesCompleted(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionLifecycle lifecycle,
            CompanionAlias alias
    ) {
        return (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED)
                && lifecycle.revision().equals(
                restoration.expectedLifecycleRevision().next().next()
        )
                && lifecycle.state()
                == com.alechilles.alecstamework.companion.lifecycle
                .LifecycleState.ACTIVE
                && lifecycle.location().equals(LifecycleLocation.liveEntity(
                restoration.targetAlias().toString(),
                restoration.targetWorldKey()
        ))
                && lifecycle.activeOperationId() == null
                && alias.state() == CompanionAlias.State.CURRENT
                && transaction.snapshots()
                .findById(restoration.sourceSnapshot().snapshotId())
                .filter(snapshot -> !snapshot.current())
                .isPresent();
    }

    static CompanionLifecycle requireRestorableSource(
            SqlitePersistenceTransactionContext transaction,
            CompanionRestorationRequest restoration
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(restoration.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "restoration_profile_lifecycle_missing"
                ));
        if (!current.revision().equals(
                restoration.expectedLifecycleRevision()
        ) || current.state() != restoration.sourceState()
                || !current.location().equals(LifecycleLocation.none())
                || current.activeOperationId() != null || current.quarantined()
                || !exactCurrentSnapshot(transaction, restoration)) {
            throw new IllegalStateException(
                    "restoration_not_exact_dormant_source"
            );
        }
        return current;
    }

    private boolean exactCurrentSnapshot(
            SqlitePersistenceTransactionContext transaction
    ) {
        return exactCurrentSnapshot(transaction, restoration);
    }

    private static boolean exactCurrentSnapshot(
            SqlitePersistenceTransactionContext transaction,
            CompanionRestorationRequest restoration
    ) {
        return transaction.snapshots()
                .findById(restoration.sourceSnapshot().snapshotId())
                .filter(restoration.sourceSnapshot()::equals)
                .filter(snapshot -> transaction.snapshots()
                        .findCurrent(
                                restoration.profileId(),
                                restoration.sourceSnapshot().kind()
                        )
                        .filter(snapshot::equals)
                        .isPresent())
                .isPresent();
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
