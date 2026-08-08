package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;

/** Atomically creates the captured source missing from an item-only public migration. */
final class SqliteCompanionCaptureReleaseOrphanPreparation {
    private SqliteCompanionCaptureReleaseOrphanPreparation() {
    }

    static CompanionLifecycle materialize(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionCaptureReleaseRequest release
    ) {
        var evidence = release.orphanRecovery();
        if (evidence == null
                || !transaction.hasSupportedPublicImport()
                || transaction.identities()
                .findProfile(release.profileId()).isPresent()
                || transaction.identities()
                .resolveAlias(release.sourceAlias()).isPresent()
                || transaction.lifecycles()
                .findByProfile(release.profileId()).isPresent()
                || transaction.snapshots()
                .findById(release.sourceSnapshot().snapshotId()).isPresent()
                || !transaction.snapshots()
                .findCurrentByProfile(release.profileId()).isEmpty()) {
            throw new IllegalStateException(
                    "capture_release_orphan_recovery_evidence_mismatch"
            );
        }
        requireApplied(
                transaction.identities().createProfile(
                        evidence.initialIdentity()
                ),
                "capture_release_orphan_identity"
        );
        requireApplied(
                transaction.identities().leaseAlias(
                        release.profileId(),
                        release.sourceAlias(),
                        operation.operationId(),
                        release.requestedAtMs()
                ),
                "capture_release_orphan_alias_lease"
        );
        requireApplied(
                transaction.identities().promoteAlias(
                        release.sourceAlias(),
                        operation.operationId(),
                        release.requestedAtMs()
                ),
                "capture_release_orphan_alias_promotion"
        );
        CompanionLifecycle captured = new CompanionLifecycle(
                release.profileId(),
                evidence.initialOwner(),
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        release.sourceSnapshot().snapshotId().toString()
                ),
                release.expectedLifecycleRevision(),
                null,
                release.requestedAtMs(),
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        requireApplied(
                transaction.lifecycles().create(captured),
                "capture_release_orphan_lifecycle"
        );
        requireApplied(
                transaction.snapshots().replaceCurrent(
                        release.sourceSnapshot()
                ),
                "capture_release_orphan_snapshot"
        );
        return captured;
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
