package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalEventCodec;
import com.alechilles.alecstamework.companion.revival.PaidRevivalOutcome;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;

/** Exact death, profile, roster, timed, alias, and lifecycle paid-revival fence. */
final class SqlitePaidRevivalPreparation
        implements PreparedOperationDetail {
    private final PaidRevivalRequest request;

    SqlitePaidRevivalPreparation(PaidRevivalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Paid revival preparation is required"
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
        requireApplied(
                transaction.identities().leaseAlias(
                        source.profileId(),
                        request.targetAlias(),
                        operation.operationId(),
                        request.requestedAtMs()
                ),
                "paid_revival_alias_lease"
        );
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
                "paid_revival_lifecycle_fence"
        );
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.sourceSnapshot().profileId())
                .orElse(null);
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.targetAlias()).orElse(null);
        if (lifecycle == null || alias == null) {
            return false;
        }
        if (fencedMatches(
                transaction, operation, lifecycle, alias
        )) {
            return true;
        }
        return switch (operation.phase()) {
            case DURABLE, PUBLISHED ->
                    successMatches(transaction, operation);
            case COMPENSATED ->
                    compensatedMatches(transaction, operation);
            default -> false;
        };
    }

    private CompanionLifecycle requireSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle source = transaction.lifecycles()
                .findByProfile(request.sourceSnapshot().profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "paid_revival_lifecycle_missing"
                ));
        if (!source.equals(request.groupAdmission().before())
                || source.activeOperationId() != null
                || source.quarantined()
                || !profileMatches(transaction)
                || !membershipMatches(transaction)
                || !snapshotMatches(transaction)
                || !timedSourceMatches(transaction)
                || transaction.identities().findCurrentAlias(
                source.profileId()
        ).isPresent()
                || transaction.identities().resolveAlias(
                request.targetAlias()
        ).isPresent()) {
            throw new IllegalStateException(
                    "paid_revival_source_mismatch"
            );
        }
        return source;
    }

    private boolean fencedMatches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionLifecycle lifecycle,
            CompanionAlias alias
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        return lifecycle.profileId().equals(source.profileId())
                && lifecycle.ownerId().equals(source.ownerId())
                && lifecycle.state() == LifecycleState.DEAD_REVIVABLE
                && lifecycle.location().equals(LifecycleLocation.none())
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
                && !lifecycle.quarantined()
                && alias.profileId().equals(source.profileId())
                && alias.state() == CompanionAlias.State.LEASED
                && operation.operationId().equals(
                alias.leaseOperationId()
        )
                && stableSourcesMatch(transaction);
    }

    private boolean successMatches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        for (ProjectionEvent event : transaction.outbox()
                .findByOperation(operation.operationId())) {
            if (event.eventType().equals(
                    PaidRevivalEventCodec.EVENT_TYPE
            )) {
                PaidRevivalOutcome outcome =
                        PaidRevivalEventCodec.decode(
                                event.payloadVersion(),
                                event.payloadJson()
                        );
                if (terminalOutcomeMatches(outcome)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean terminalOutcomeMatches(PaidRevivalOutcome outcome) {
        TimedSummonActivation timed = request.timedActivation();
        return outcome.profileId().equals(
                request.sourceSnapshot().profileId()
        )
                && outcome.sourceSnapshotId().equals(
                request.sourceSnapshot().snapshotId()
        )
                && outcome.liveAlias().equals(request.targetAlias())
                && outcome.worldKey().equals(request.targetWorldKey())
                && outcome.lifecycleRevision().equals(
                request.finalLifecycle().revision()
        )
                && outcome.configRevision().equals(
                request.configRevision()
        )
                && outcome.exactCost().equals(request.exactCost())
                && outcome.chargeReceiptKey().equals(
                request.chargeReceiptKey()
        )
                && outcome.spawnReceiptKey().equals(
                request.spawnReceiptKey()
        )
                && java.util.Objects.equals(
                outcome.timedSessionId(),
                timed == null ? null : timed.lease().sessionId()
        );
    }

    boolean compensatedMatches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.sourceSnapshot().profileId())
                .orElse(null);
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.targetAlias()).orElse(null);
        if (!compensatedLifecycleMatches(lifecycle)
                || alias == null
                || alias.state() != CompanionAlias.State.RETIRED
                || !alias.profileId().equals(
                request.sourceSnapshot().profileId()
        )
                || !snapshotMatches(transaction)
                || !timedSourceMatches(transaction)
                || !transaction.populationGroups()
                .findReservations(operation.operationId()).isEmpty()) {
            return false;
        }
        RefundClaim claim = transaction.refunds()
                .findByOperation(operation.operationId()).orElse(null);
        return claim == null || (claim.delivered()
                && SqlitePaidRevivalRefunds.same(
                SqlitePaidRevivalRefunds.claim(
                        operation.operationId(), request
                ),
                claim
        ));
    }

    private boolean compensatedLifecycleMatches(
            CompanionLifecycle lifecycle
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        return lifecycle != null
                && lifecycle.profileId().equals(source.profileId())
                && lifecycle.ownerId().equals(source.ownerId())
                && lifecycle.state() == LifecycleState.DEAD_REVIVABLE
                && lifecycle.location().equals(LifecycleLocation.none())
                && lifecycle.revision().equals(
                source.revision().next().next()
        )
                && lifecycle.activeOperationId() == null
                && lifecycle.lastReconciledGeneration().equals(
                source.lastReconciledGeneration()
        )
                && java.util.Objects.equals(
                lifecycle.ownerWorldKey(), source.ownerWorldKey()
        )
                && !lifecycle.quarantined();
    }

    private boolean stableSourcesMatch(
            SqlitePersistenceTransactionContext transaction
    ) {
        return profileMatches(transaction)
                && membershipMatches(transaction)
                && snapshotMatches(transaction)
                && timedSourceMatches(transaction);
    }

    private boolean profileMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionIdentity profile = transaction.identities()
                .findProfile(request.sourceSnapshot().profileId())
                .orElse(null);
        return profile != null
                && profile.metadataRevision()
                == request.expectedProfileRevision();
    }

    private boolean membershipMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        try {
            SqliteCommandRosterEvidence.requireExact(
                    transaction,
                    request.sourceSnapshot().profileId(),
                    request.familyKey(),
                    request.slotId(),
                    request.expectedMembershipRevision()
            );
            return true;
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private boolean snapshotMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        return transaction.snapshots()
                .findById(request.sourceSnapshot().snapshotId())
                .filter(request.sourceSnapshot()::equals)
                .filter(snapshot -> transaction.snapshots()
                        .findCurrent(
                                snapshot.profileId(), snapshot.kind()
                        )
                        .filter(snapshot::equals).isPresent())
                .isPresent();
    }

    private boolean timedSourceMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        TimedSummonActivation timed = request.timedActivation();
        return timed == null || transaction.timedSummons()
                .find(request.sourceSnapshot().profileId())
                .map(actual -> actual.equals(
                        timed.expectedPreviousLease()
                ))
                .orElse(timed.expectedPreviousLease() == null);
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
