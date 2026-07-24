package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;

/** Atomically leases a target alias and fences the exact captured lifecycle and snapshot. */
final class SqliteCompanionCaptureReleasePreparation
        implements PreparedOperationDetail {
    private final CompanionCaptureReleaseRequest release;

    SqliteCompanionCaptureReleasePreparation(
            CompanionCaptureReleaseRequest release
    ) {
        if (release == null) {
            throw new IllegalArgumentException(
                    "Captured-artifact release preparation is required"
            );
        }
        this.release = release;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle current = requireExactCapturedSource(transaction);
        requireApplied(
                transaction.identities().leaseAlias(
                        release.profileId(),
                        release.targetAlias(),
                        operation.operationId(),
                        release.requestedAtMs()
                ),
                "capture_release_alias_lease"
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
                current.quarantineIncidentId(),
                current.ownerWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        current.revision(),
                        null,
                        fenced
                )),
                "capture_release_lifecycle_fence"
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
        CompanionAlias alias = transaction.identities()
                .resolveAlias(release.targetAlias())
                .orElse(null);
        CompanionAlias sourceAlias = transaction.identities()
                .resolveAlias(release.sourceAlias())
                .orElse(null);
        if (lifecycle == null || alias == null
                || sourceAlias == null
                || !release.profileId().equals(alias.profileId())
                || !release.profileId().equals(sourceAlias.profileId())) {
            return false;
        }
        boolean fenced = lifecycle.revision().equals(
                release.expectedLifecycleRevision().next()
        )
                && lifecycle.state() == LifecycleState.CAPTURED
                && lifecycle.location().equals(capturedLocation())
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                && (release.ownerAssignment() == null
                || lifecycle.ownerId() == null)
                && alias.state() == CompanionAlias.State.LEASED
                && operation.operationId().equals(alias.leaseOperationId())
                && sourceAlias.state() == CompanionAlias.State.CURRENT
                && exactAliasLineage(
                        operation,
                        sourceAlias,
                        alias,
                        false
                )
                && exactCurrentSnapshot(transaction);
        if (fenced) {
            return true;
        }
        return matchesCompleted(
                transaction,
                operation,
                lifecycle,
                sourceAlias,
                alias
        );
    }

    private boolean matchesCompleted(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionLifecycle lifecycle,
            CompanionAlias sourceAlias,
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
                && (release.ownerAssignment() == null
                || release.ownerAssignment().equals(lifecycle.ownerId()))
                && alias.state() == CompanionAlias.State.CURRENT
                && operation.operationId().equals(alias.leaseOperationId())
                && sourceAlias.state() == CompanionAlias.State.RETIRED
                && exactAliasLineage(
                        operation,
                        sourceAlias,
                        alias,
                        true
                )
                && transaction.snapshots()
                .findById(release.sourceSnapshot().snapshotId())
                .filter(this::exactRetiredSourceSnapshot)
                .isPresent()
                && transaction.snapshots().findCurrent(
                        release.profileId(),
                        release.sourceSnapshot().kind()
                ).isEmpty();
    }

    private boolean exactAliasLineage(
            OperationEnvelope operation,
            CompanionAlias sourceAlias,
            CompanionAlias targetAlias,
            boolean completed
    ) {
        return targetAlias.mappedAtMs() == release.requestedAtMs()
                && targetAlias.generation() > sourceAlias.generation()
                && (!completed || java.util.Objects.equals(
                        sourceAlias.retiredAtMs(),
                        operation.durableAtMs()
                ));
    }

    private CompanionLifecycle requireExactCapturedSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(release.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "capture_release_profile_lifecycle_missing"
                ));
        CompanionAlias sourceAlias = transaction.identities()
                .resolveAlias(release.sourceAlias())
                .orElse(null);
        if (!current.revision().equals(
                release.expectedLifecycleRevision()
        )
                || current.state() != LifecycleState.CAPTURED
                || !current.location().equals(capturedLocation())
                || current.activeOperationId() != null
                || current.quarantined()
                || (release.ownerAssignment() != null
                && current.ownerId() != null)) {
            throw new IllegalStateException(
                    "capture_release_captured_lifecycle_mismatch"
            );
        }
        if (sourceAlias == null
                || !release.profileId().equals(sourceAlias.profileId())
                || sourceAlias.state() != CompanionAlias.State.CURRENT) {
            throw new IllegalStateException(
                    "capture_release_source_alias_mismatch"
            );
        }
        if (!exactCurrentSnapshot(transaction)) {
            throw new IllegalStateException(
                    "capture_release_source_snapshot_mismatch"
            );
        }
        return current;
    }

    private LifecycleLocation capturedLocation() {
        return LifecycleLocation.keyed(
                LifecycleLocationKind.CAPTURE_ITEM,
                release.sourceSnapshot().snapshotId().toString()
        );
    }

    private boolean exactCurrentSnapshot(
            SqlitePersistenceTransactionContext transaction
    ) {
        return transaction.snapshots()
                .findById(release.sourceSnapshot().snapshotId())
                .filter(release.sourceSnapshot()::equals)
                .filter(snapshot -> transaction.snapshots()
                        .findCurrent(
                                release.profileId(),
                                release.sourceSnapshot().kind()
                        )
                        .filter(snapshot::equals)
                        .isPresent())
                .isPresent();
    }

    private boolean exactRetiredSourceSnapshot(CompanionSnapshot snapshot) {
        CompanionSnapshot source = release.sourceSnapshot();
        return snapshot.equals(new CompanionSnapshot(
                source.snapshotId(),
                source.profileId(),
                source.kind(),
                source.payloadVersion(),
                source.payloadJson(),
                source.payloadHash(),
                source.sourceLifecycleRevision(),
                false,
                source.createdAtMs()
        ));
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
