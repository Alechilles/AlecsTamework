package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;

/** Variant-aware lifecycle preparation for the shared capture operation. */
final class SqliteCompanionCapturePreparation
        implements PreparedOperationDetail {
    private final CompanionCaptureRequest capture;

    SqliteCompanionCapturePreparation(CompanionCaptureRequest capture) {
        if (capture == null) {
            throw new IllegalArgumentException(
                    "Capture preparation request is required"
            );
        }
        this.capture = capture;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle current = requireExactLive(transaction);
        if (capture.failedAttempt()) {
            return;
        }
        CompanionLifecycle fenced = new CompanionLifecycle(
                current.profileId(),
                current.ownerId(),
                current.state(),
                current.location(),
                current.revision().next(),
                operation.operationId(),
                capture.requestedAtMs(),
                current.lastReconciledGeneration(),
                current.quarantineIncidentId(),
                current.ownerWorldKey()
        );
        var result = transaction.lifecycles().transition(
                new LifecycleTransition(
                        current.revision(), null, fenced
                )
        );
        if (!result.applied()) {
            throw new IllegalStateException(
                    "capture_prepare_fence_"
                            + result.status().name().toLowerCase()
            );
        }
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (capture.failedAttempt()) {
            return matchesFailed(transaction, operation);
        }
        return matchesCaptured(transaction, operation);
    }

    private boolean matchesFailed(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            return true;
        }
        if (operation.phase() == OperationPhase.COMPENSATED) {
            return SqliteCaptureCompensation.matchesCompleted(
                    transaction, operation, capture
            );
        }
        try {
            requireExactLive(transaction);
            return operation.phase() == OperationPhase.PREPARED
                    || operation.phase() == OperationPhase.RETRYABLE
                    || operation.phase() == OperationPhase.LIVE_APPLYING
                    || operation.phase() == OperationPhase.COMPENSATING;
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private boolean matchesCaptured(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElse(null);
        if (lifecycle == null) {
            return false;
        }
        boolean exactFence = capture.tameAndCommandLink()
                ? matchesFence(
                lifecycle,
                operation,
                capture.tameAndLinkEvidence().expectedLifecycle(),
                capture.requestedAtMs()
        )
                : lifecycle.revision().equals(
                capture.expectedLifecycleRevision().next()
        ) && operation.operationId().equals(
                lifecycle.activeOperationId()
        );
        if (exactFence) {
            return true;
        }
        if (operation.phase() == OperationPhase.COMPENSATED
                && SqliteCaptureCompensation.matchesCompleted(
                transaction, operation, capture
        )) {
            return true;
        }
        if (capture.tameAndCommandLink()) {
            return (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED)
                    && SqliteCompanionCaptureTameCommit.matchesDurable(
                    transaction, capture.tameAndLinkEvidence()
            );
        }
        return (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED)
                && lifecycle.revision().equals(
                capture.expectedLifecycleRevision().next().next()
        )
                && lifecycle.activeOperationId() == null
                && lifecycle.state() == LifecycleState.CAPTURED
                && lifecycle.location().kind()
                == LifecycleLocationKind.CAPTURE_ITEM
                && capture.snapshot().snapshotId().toString().equals(
                lifecycle.location().key()
        )
                && transaction.snapshots()
                .findById(capture.snapshot().snapshotId())
                .filter(capture.snapshot()::equals)
                .isPresent();
    }

    private CompanionLifecycle requireExactLive(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "capture_profile_lifecycle_missing"
                ));
        CompanionAlias alias = transaction.identities()
                .resolveAlias(capture.targetAlias())
                .orElse(null);
        boolean exactLiveLocation =
                current.state() == LifecycleState.ACTIVE
                        && current.location().kind()
                        == LifecycleLocationKind.LIVE_ENTITY
                        && capture.targetAlias().toString().equals(
                        current.location().key()
                )
                        && capture.targetWorldKey().equals(
                        current.location().worldKey()
                );
        if (!current.revision().equals(
                capture.expectedLifecycleRevision()
        )
                || current.activeOperationId() != null
                || current.quarantined()
                || !exactLiveLocation
                || alias == null
                || !alias.profileId().equals(capture.profileId())
                || alias.state() != CompanionAlias.State.CURRENT) {
            throw new IllegalStateException(
                    "capture_prepare_not_exact_live_profile"
            );
        }
        return current;
    }

    static boolean matchesFence(
            CompanionLifecycle lifecycle,
            OperationEnvelope operation,
            CompanionLifecycle source,
            long fencedAtMs
    ) {
        return lifecycle != null
                && operation != null
                && source != null
                && lifecycle.profileId().equals(source.profileId())
                && java.util.Objects.equals(
                lifecycle.ownerId(), source.ownerId()
        )
                && lifecycle.state() == source.state()
                && lifecycle.location().equals(source.location())
                && lifecycle.revision().equals(source.revision().next())
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                && lifecycle.stateChangedAtMs() == fencedAtMs
                && lifecycle.lastReconciledGeneration().equals(
                source.lastReconciledGeneration()
        )
                && java.util.Objects.equals(
                lifecycle.quarantineIncidentId(),
                source.quarantineIncidentId()
        )
                && java.util.Objects.equals(
                lifecycle.ownerWorldKey(), source.ownerWorldKey()
        );
    }
}
