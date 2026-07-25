package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSource;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;

/**
 * Exact SQLite authority checks for each supported coop-capture source variant.
 *
 * <p>Captured inventory artifacts derive their authority from the canonical capture snapshot and
 * lifecycle. The item receipt is live-boundary evidence and never creates a second durable source
 * record.</p>
 */
final class SqliteCompanionCoopCaptureSourceAuthority {
    private final CompanionCoopCaptureRequest capture;

    SqliteCompanionCoopCaptureSourceAuthority(
            CompanionCoopCaptureRequest capture
    ) {
        if (capture == null) {
            throw new IllegalArgumentException(
                    "Coop capture source authority is required"
            );
        }
        this.capture = capture;
    }

    CompanionLifecycle requireExactSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "coop_capture_profile_lifecycle_missing"
                ));
        if (!current.revision().equals(
                capture.expectedLifecycleRevision()
        )
                || current.activeOperationId() != null
                || current.quarantined()
                || !matchesSourceLocation(current)
                || !matchesAlias(transaction)) {
            throw new IllegalStateException(
                    "coop_capture_not_exact_"
                            + sourceLabel() + "_source"
            );
        }
        requireCapturedSnapshot(transaction);
        return current;
    }

    boolean matchesFence(
            SqlitePersistenceTransactionContext transaction,
            CompanionLifecycle lifecycle,
            OperationEnvelope operation
    ) {
        if (lifecycle == null
                || !lifecycle.revision().equals(
                capture.expectedLifecycleRevision().next()
        )
                || !operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                || lifecycle.quarantined()
                || !matchesSourceLocation(lifecycle)
                || !matchesAlias(transaction)) {
            return false;
        }
        return matchesCurrentCapturedSnapshot(transaction);
    }

    boolean matchesDurableSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        if (!matchesAlias(transaction)) {
            return false;
        }
        if (!(capture.source()
                instanceof CoopCapturedItemSourceEvidence capturedItem)) {
            return true;
        }
        CompanionSnapshot retired = retired(capturedItem.captureSnapshot());
        return transaction.snapshots()
                .findById(retired.snapshotId())
                .filter(retired::equals)
                .isPresent()
                && transaction.snapshots()
                .findCurrent(
                        retired.profileId(), retired.kind()
                )
                .isEmpty();
    }

    void retireCapturedSnapshot(
            SqlitePersistenceTransactionContext transaction
    ) {
        if (!(capture.source()
                instanceof CoopCapturedItemSourceEvidence capturedItem)) {
            return;
        }
        requireCapturedSnapshot(transaction);
        requireApplied(
                transaction.snapshots().retireCurrent(
                        capturedItem.captureSnapshot().snapshotId()
                ),
                "coop_capture_source_snapshot_retirement"
        );
    }

    private boolean matchesSourceLocation(CompanionLifecycle lifecycle) {
        if (capture.source().kind()
                == CoopCaptureSource.Kind.LIVE_ENTITY) {
            return lifecycle.state() == LifecycleState.ACTIVE
                    && lifecycle.location().kind()
                    == LifecycleLocationKind.LIVE_ENTITY
                    && capture.source().sourceAlias().toString().equals(
                    lifecycle.location().key()
            )
                    && capture.source().sourceWorldKey().equals(
                    lifecycle.location().worldKey()
            );
        }
        CoopCapturedItemSourceEvidence item =
                (CoopCapturedItemSourceEvidence) capture.source();
        return lifecycle.state() == LifecycleState.CAPTURED
                && lifecycle.location().kind()
                == LifecycleLocationKind.CAPTURE_ITEM
                && item.captureSnapshot().snapshotId().toString().equals(
                lifecycle.location().key()
        );
    }

    private boolean matchesAlias(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionAlias alias = transaction.identities()
                .resolveAlias(capture.source().sourceAlias())
                .orElse(null);
        return alias != null
                && capture.profileId().equals(alias.profileId())
                && alias.state() == CompanionAlias.State.CURRENT;
    }

    private void requireCapturedSnapshot(
            SqlitePersistenceTransactionContext transaction
    ) {
        if (!matchesCurrentCapturedSnapshot(transaction)) {
            throw new IllegalStateException(
                    "coop_capture_not_exact_captured_item_snapshot"
            );
        }
    }

    private boolean matchesCurrentCapturedSnapshot(
            SqlitePersistenceTransactionContext transaction
    ) {
        if (!(capture.source()
                instanceof CoopCapturedItemSourceEvidence capturedItem)) {
            return true;
        }
        CompanionSnapshot expected = capturedItem.captureSnapshot();
        return transaction.snapshots()
                .findById(expected.snapshotId())
                .filter(expected::equals)
                .isPresent()
                && transaction.snapshots()
                .findCurrent(expected.profileId(), expected.kind())
                .filter(expected::equals)
                .isPresent();
    }

    private String sourceLabel() {
        return capture.source().kind().name().toLowerCase();
    }

    private static CompanionSnapshot retired(CompanionSnapshot current) {
        return new CompanionSnapshot(
                current.snapshotId(),
                current.profileId(),
                current.kind(),
                current.payloadVersion(),
                current.payloadJson(),
                current.payloadHash(),
                current.sourceLifecycleRevision(),
                false,
                current.createdAtMs()
        );
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
