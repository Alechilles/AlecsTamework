package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecordChangeCodec;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;

/** Exact absent-source and immutable completion proof for dormant provisioning. */
final class SqliteCompanionProvisioningPreparation
        implements PreparedOperationDetail {
    private final CompanionProvisioningRequest request;

    SqliteCompanionProvisioningPreparation(
            CompanionProvisioningRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Provisioning preparation request is required"
            );
        }
        this.request = request;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireAbsent(transaction);
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            return completed(transaction, operation);
        }
        try {
            requireAbsent(transaction);
            return operation.phase() == OperationPhase.PREPARED
                    || operation.phase() == OperationPhase.RETRYABLE;
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private void requireAbsent(
            SqlitePersistenceTransactionContext transaction
    ) {
        boolean occupied = transaction.identities()
                .findProfile(request.origin().profileId()).isPresent()
                || transaction.lifecycles()
                .findByProfile(request.origin().profileId()).isPresent()
                || transaction.populationGroups()
                .findAssignment(request.origin().profileId()).isPresent()
                || transaction.provisioning()
                .findByProfile(request.origin().profileId()).isPresent()
                || transaction.provisioning()
                .findByOrigin(request.origin()).isPresent()
                || transaction.commandRosters()
                .findByProfile(request.origin().profileId()).isPresent();
        if (request.commandMembership() != null) {
            occupied = occupied || transaction.commandRosters()
                    .findBySlot(request.origin().commandSlotId())
                    .isPresent()
                    || !rosterRevisionMatches(transaction);
        }
        if (occupied) {
            throw new IllegalStateException(
                    "provisioning_source_already_exists"
            );
        }
    }

    private boolean rosterRevisionMatches(
            SqlitePersistenceTransactionContext transaction
    ) {
        CommandRoster roster = transaction.commandRosters()
                .findRoster(request.commandMembership().familyKey())
                .orElse(null);
        long revision = roster == null ? 0 : roster.rosterRevision();
        return request.expectedCommandRosterRevision() != null
                && revision == request.expectedCommandRosterRevision();
    }

    private boolean completed(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        ProvisioningRecord expected = new ProvisioningRecord(
                request.origin().profileId(),
                request.origin(),
                request.correlationId(),
                request.groupAssignment().policyRevision(),
                operation.operationId(),
                request.requestedAtMs()
        );
        if (!transaction.provisioning().findByProfile(
                request.origin().profileId()
        ).filter(expected::equals).isPresent()
                || transaction.identities().findProfile(
                request.origin().profileId()
        ).isEmpty()
                || transaction.lifecycles().findByProfile(
                request.origin().profileId()
        ).isEmpty()
                || transaction.populationGroups().findAssignment(
                request.origin().profileId()
        ).isEmpty()) {
            return false;
        }
        for (ProjectionEvent event : transaction.outbox()
                .findByOperation(operation.operationId())) {
            if (event.eventType().equals(
                    ProvisioningRecordChangeCodec.EVENT_TYPE
            ) && ProvisioningRecordChangeCodec.decode(
                    event.payloadVersion(), event.payloadJson()
            ).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    static boolean membershipMatches(
            CommandRosterMembership actual,
            CommandRosterMembershipDraft expected
    ) {
        return actual != null
                && actual.slotId().equals(expected.slotId())
                && actual.familyKey().equals(expected.familyKey())
                && actual.profileId().equals(expected.profileId())
                && java.util.Objects.equals(
                actual.groupId(), expected.groupId()
        )
                && actual.activeForBulkCommands()
                == expected.activeForBulkCommands()
                && java.util.Objects.equals(actual.home(), expected.home())
                && actual.createdAtMs() == expected.changedAtMs()
                && actual.updatedAtMs() == expected.changedAtMs();
    }
}

