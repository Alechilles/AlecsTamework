package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;

/** Atomically leases a target alias and fences the exact occupied coop slot and lifecycle. */
final class SqliteCompanionCoopReleasePreparation
        implements PreparedOperationDetail {
    private final CompanionCoopReleaseRequest release;

    SqliteCompanionCoopReleasePreparation(
            CompanionCoopReleaseRequest release
    ) {
        if (release == null) {
            throw new IllegalArgumentException("Coop release preparation is required");
        }
        this.release = release;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle current = requireExactCoopSource(transaction);
        CoopConflictDiagnostic conflict = transaction.coops().diagnoseRelease(
                release.sourceResidency().slotKey(), release.profileId()
        );
        if (conflict.reason() != CoopConflictDiagnostic.Reason.NONE
                || !release.sourceResidency().equals(
                conflict.slotResidency()
        )) {
            throw new IllegalStateException(
                    "coop_release_conflict_"
                            + (conflict.reason()
                            == CoopConflictDiagnostic.Reason.NONE
                            ? "residency_changed"
                            : conflict.reason().name().toLowerCase())
            );
        }
        requireApplied(
                transaction.identities().leaseAlias(
                        release.profileId(),
                        release.targetAlias(),
                        operation.operationId(),
                        release.requestedAtMs()
                ),
                "coop_release_alias_lease"
        );
        requireApplied(
                transaction.coops().reserveOccupied(
                        release.sourceResidency().slotKey(),
                        release.profileId(),
                        operation.operationId()
                ),
                "coop_release_slot_reservation"
        );
        CompanionLifecycle fenced = new CompanionLifecycle(
                current.profileId(),
                current.ownerId(),
                current.state(),
                current.location(),
                current.revision().next(),
                operation.operationId(),
                release.requestedAtMs(),
                current.lastReconciledGeneration(),
                current.quarantineIncidentId()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        current.revision(), null, fenced
                )),
                "coop_release_lifecycle_fence"
        );
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(release.profileId())
                .orElse(null);
        CoopSlot slot = transaction.coops()
                .findSlot(release.sourceResidency().slotKey())
                .orElse(null);
        CompanionAlias alias = transaction.identities()
                .resolveAlias(release.targetAlias())
                .orElse(null);
        if (lifecycle == null || slot == null || alias == null
                || !release.profileId().equals(alias.profileId())) {
            return false;
        }
        boolean fenced = lifecycle.revision().equals(
                release.expectedLifecycleRevision().next()
        )
                && lifecycle.state() == LifecycleState.COOP
                && operation.operationId().equals(lifecycle.activeOperationId())
                && operation.operationId().equals(slot.activeOperationId())
                && release.profileId().equals(slot.reservedProfileId())
                && alias.state() == CompanionAlias.State.LEASED
                && operation.operationId().equals(alias.leaseOperationId())
                && exactCurrentSource(transaction);
        if (fenced) {
            return true;
        }
        return matchesCompleted(transaction, operation, lifecycle, slot, alias);
    }

    private boolean matchesCompleted(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionLifecycle lifecycle,
            CoopSlot slot,
            CompanionAlias alias
    ) {
        return (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED)
                && lifecycle.revision().equals(
                release.expectedLifecycleRevision().next().next()
        )
                && lifecycle.state() == LifecycleState.ACTIVE
                && lifecycle.location().equals(LifecycleLocation.liveEntity(
                release.targetAlias().toString(),
                release.targetWorldKey()
        ))
                && lifecycle.activeOperationId() == null
                && !slot.reserved()
                && alias.state() == CompanionAlias.State.CURRENT
                && transaction.coops()
                .findResidencyBySlot(release.sourceResidency().slotKey())
                .isEmpty()
                && transaction.snapshots()
                .findById(release.sourceSnapshot().snapshotId())
                .filter(snapshot -> !snapshot.current())
                .isPresent();
    }

    private CompanionLifecycle requireExactCoopSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(release.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "coop_release_profile_lifecycle_missing"
                ));
        LifecycleLocation expectedLocation = LifecycleLocation.keyed(
                com.alechilles.alecstamework.companion.lifecycle
                        .LifecycleLocationKind.COOP_SLOT,
                release.sourceResidency().slotKey().toString()
        );
        if (!current.revision().equals(release.expectedLifecycleRevision())
                || current.state() != LifecycleState.COOP
                || !current.location().equals(expectedLocation)
                || current.activeOperationId() != null || current.quarantined()
                || !exactCurrentSource(transaction)) {
            throw new IllegalStateException(
                    "coop_release_not_exact_resident_source"
            );
        }
        return current;
    }

    private boolean exactCurrentSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CoopResidency residency = transaction.coops()
                .findResidencyByProfile(release.profileId())
                .orElse(null);
        return release.sourceResidency().equals(residency)
                && transaction.snapshots()
                .findById(release.sourceSnapshot().snapshotId())
                .filter(release.sourceSnapshot()::equals)
                .filter(snapshot -> transaction.snapshots()
                        .findCurrent(release.profileId(), snapshot.kind())
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
