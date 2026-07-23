package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;

/** Atomically reserves one empty coop slot and fences its exact live source lifecycle. */
final class SqliteCompanionCoopCapturePreparation
        implements PreparedOperationDetail {
    private final CompanionCoopCaptureRequest capture;

    SqliteCompanionCoopCapturePreparation(CompanionCoopCaptureRequest capture) {
        if (capture == null) {
            throw new IllegalArgumentException("Coop capture preparation is required");
        }
        this.capture = capture;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle current = requireExactLiveSource(transaction);
        CoopConflictDiagnostic conflict = transaction.coops().diagnoseCapture(
                capture.targetSlot(), capture.profileId()
        );
        if (conflict.reason() != CoopConflictDiagnostic.Reason.NONE) {
            throw new IllegalStateException(
                    "coop_capture_conflict_"
                            + conflict.reason().name().toLowerCase()
            );
        }
        requireApplied(
                transaction.coops().reserveEmpty(
                        capture.targetSlot(),
                        capture.profileId(),
                        operation.operationId()
                ),
                "coop_capture_slot_reservation"
        );
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
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        current.revision(), null, fenced
                )),
                "coop_capture_lifecycle_fence"
        );
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElse(null);
        CoopSlot slot = transaction.coops().findSlot(
                capture.targetSlot()
        ).orElse(null);
        if (lifecycle == null || slot == null) {
            return false;
        }
        boolean fenced = lifecycle.revision().equals(
                capture.expectedLifecycleRevision().next()
        ) && lifecycle.state() == LifecycleState.ACTIVE
                && operation.operationId().equals(lifecycle.activeOperationId())
                && operation.operationId().equals(slot.activeOperationId())
                && capture.profileId().equals(slot.reservedProfileId())
                && transaction.coops()
                .findResidencyBySlot(capture.targetSlot())
                .isEmpty();
        if (fenced) {
            return true;
        }
        return matchesCompleted(transaction, operation, lifecycle, slot);
    }

    private boolean matchesCompleted(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionLifecycle lifecycle,
            CoopSlot slot
    ) {
        CoopResidency residency = transaction.coops()
                .findResidencyBySlot(capture.targetSlot())
                .orElse(null);
        return (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED)
                && lifecycle.revision().equals(
                capture.expectedLifecycleRevision().next().next()
        )
                && lifecycle.state() == LifecycleState.COOP
                && lifecycle.location().kind()
                == LifecycleLocationKind.COOP_SLOT
                && capture.targetSlot().toString().equals(
                lifecycle.location().key()
        )
                && lifecycle.activeOperationId() == null
                && !slot.reserved()
                && exactResidency(residency)
                && transaction.snapshots()
                .findById(capture.snapshot().snapshotId())
                .filter(capture.snapshot()::equals)
                .isPresent();
    }

    private CompanionLifecycle requireExactLiveSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(capture.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "coop_capture_profile_lifecycle_missing"
                ));
        CompanionAlias alias = transaction.identities()
                .resolveAlias(capture.source().sourceAlias())
                .orElse(null);
        boolean exactLocation = current.state() == LifecycleState.ACTIVE
                && current.location().kind() == LifecycleLocationKind.LIVE_ENTITY
                && capture.source().sourceAlias().toString().equals(
                current.location().key()
        )
                && capture.source().sourceWorldKey().equals(
                current.location().worldKey()
        );
        if (!current.revision().equals(capture.expectedLifecycleRevision())
                || current.activeOperationId() != null || current.quarantined()
                || !exactLocation || alias == null
                || !capture.profileId().equals(alias.profileId())
                || alias.state() != CompanionAlias.State.CURRENT) {
            throw new IllegalStateException(
                    "coop_capture_not_exact_live_source"
            );
        }
        return current;
    }

    private boolean exactResidency(CoopResidency residency) {
        return residency != null
                && capture.profileId().equals(residency.profileId())
                && capture.targetSlot().equals(residency.slotKey())
                && capture.source().sourceAlias().equals(
                residency.housedNpcAlias()
        )
                && capture.snapshot().snapshotId().equals(
                residency.snapshotId()
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
