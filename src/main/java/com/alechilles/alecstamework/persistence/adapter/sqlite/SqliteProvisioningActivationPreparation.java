package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationEventCodec;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationOutcome;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;

/** Exact provenance, alias lease, and lifecycle fence for initial activation. */
final class SqliteProvisioningActivationPreparation
        implements PreparedOperationDetail {
    private final ProvisioningActivationRequest request;

    SqliteProvisioningActivationPreparation(
            ProvisioningActivationRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Provisioning activation preparation is required"
            );
        }
        this.request = request;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle current = requireSource(transaction);
        requireApplied(
                transaction.identities().leaseAlias(
                        request.origin().profileId(),
                        request.targetAlias(),
                        operation.operationId(),
                        request.requestedAtMs()
                ),
                "provisioning_activation_alias_lease"
        );
        CompanionLifecycle fenced = new CompanionLifecycle(
                current.profileId(),
                current.ownerId(),
                current.state(),
                current.location(),
                current.revision().next(),
                operation.operationId(),
                request.requestedAtMs(),
                current.lastReconciledGeneration(),
                current.quarantineIncidentId(),
                current.ownerWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                current.revision(), null, fenced
                        )
                ),
                "provisioning_activation_lifecycle_fence"
        );
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.origin().profileId())
                .orElse(null);
        CompanionAlias alias = transaction.identities()
                .resolveAlias(request.targetAlias()).orElse(null);
        if (lifecycle == null || alias == null
                || !recordMatches(transaction)) {
            return false;
        }
        if (fencedMatches(lifecycle, alias, operation)
                && timedPreparedMatches(transaction)) {
            return true;
        }
        return completedMatches(transaction, operation);
    }

    private CompanionLifecycle requireSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(request.origin().profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "provisioning_activation_lifecycle_missing"
                ));
        if (!current.equals(request.groupAdmission().before())
                || current.activeOperationId() != null
                || current.quarantined()
                || !recordMatches(transaction)
                || transaction.identities().findCurrentAlias(
                request.origin().profileId()
        ).isPresent()
                || transaction.identities().resolveAlias(
                request.targetAlias()
        ).isPresent()
                || !timedSourceMatches(transaction)) {
            throw new IllegalStateException(
                    "provisioning_activation_source_mismatch"
            );
        }
        return current;
    }

    private boolean recordMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        var identity = transaction.identities()
                .findProfile(request.origin().profileId())
                .orElse(null);
        return identity != null
                && request.expectedRoleId().equals(identity.roleId())
                && request.fullState().payloadHash().matchesUtf8(
                request.fullState().payloadJson()
        )
                && transaction.provisioning()
                .findByOrigin(request.origin())
                .filter(record -> record.profileId().equals(
                        request.origin().profileId()
                ))
                .isPresent();
    }

    private boolean timedSourceMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        if (request.timedActivation() == null) {
            return true;
        }
        try {
            requireCommand(transaction, request.timedActivation());
            return transaction.timedSummons()
                    .find(request.origin().profileId()).isEmpty();
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private boolean fencedMatches(
            CompanionLifecycle lifecycle,
            CompanionAlias alias,
            OperationEnvelope operation
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        return lifecycle.profileId().equals(source.profileId())
                && lifecycle.ownerId().equals(source.ownerId())
                && lifecycle.state() == source.state()
                && lifecycle.location().equals(source.location())
                && lifecycle.revision().equals(
                source.revision().next()
        )
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                && lifecycle.stateChangedAtMs()
                == request.requestedAtMs()
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
        );
    }

    private boolean timedPreparedMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        return timedSourceMatches(transaction);
    }

    private boolean completedMatches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if ((operation.phase() != OperationPhase.DURABLE
                && operation.phase() != OperationPhase.PUBLISHED)) {
            return false;
        }
        for (ProjectionEvent event : transaction.outbox()
                .findByOperation(operation.operationId())) {
            if (!event.eventType().equals(
                    ProvisioningActivationEventCodec.EVENT_TYPE
            )) {
                continue;
            }
            ProvisioningActivationOutcome outcome =
                    ProvisioningActivationEventCodec.decode(
                            event.payloadVersion(), event.payloadJson()
                    );
            if (terminalOutcomeMatches(outcome)) {
                return true;
            }
        }
        return false;
    }

    private boolean terminalOutcomeMatches(
            ProvisioningActivationOutcome outcome
    ) {
        TimedSummonActivation timed = request.timedActivation();
        return outcome.profileId().equals(request.origin().profileId())
                && outcome.liveAlias().equals(request.targetAlias())
                && outcome.worldKey().equals(request.targetWorldKey())
                && outcome.lifecycleRevision().equals(
                request.finalLifecycle().revision()
        )
                && outcome.receiptKey().equals(
                request.spawnReceiptKey()
        )
                && java.util.Objects.equals(
                outcome.timedSessionId(),
                timed == null ? null : timed.lease().sessionId()
        );
    }

    private void requireCommand(
            SqlitePersistenceTransactionContext transaction,
            TimedSummonActivation timed
    ) {
        SqliteCommandRosterEvidence.requireExact(
                transaction,
                request.origin().profileId(),
                timed.familyKey(),
                timed.slotId(),
                timed.expectedMembershipRevision()
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
