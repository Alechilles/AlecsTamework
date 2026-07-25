package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import javax.annotation.Nonnull;

/**
 * Exact pre-live command-family, slot, membership, and timed-lease evidence for tame/link capture.
 */
final class SqliteCaptureCommandActivationParticipant
        implements PreparedOperationDetail {
    private final CaptureTameAndLinkEvidence evidence;

    SqliteCaptureCommandActivationParticipant(
            @Nonnull CaptureTameAndLinkEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Capture command activation evidence is required"
            );
        }
        this.evidence = evidence;
    }

    @Override
    public void prepare(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        requireSource(transaction);
    }

    @Override
    public boolean matches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED) {
            return SqliteCompanionCaptureTameCommit.matchesCommandTarget(
                    transaction, evidence
            );
        }
        if (operation.phase() == OperationPhase.COMPENSATED
                || operation.phase() == OperationPhase.FAILED) {
            return true;
        }
        try {
            requireSource(transaction);
            return true;
        } catch (IllegalStateException invalid) {
            return false;
        }
    }

    private void requireSource(
            SqlitePersistenceTransactionContext transaction
    ) {
        CommandRoster roster = transaction.commandRosters()
                .findRoster(evidence.rosterMembership().familyKey())
                .orElse(null);
        long rosterRevision = roster == null
                ? 0
                : roster.rosterRevision();
        if (transaction.commandRosters().findByProfile(
                evidence.expectedIdentity().profileId()
        ).isPresent()
                || transaction.commandRosters().findBySlot(
                evidence.rosterMembership().slotId()
        ).isPresent()
                || rosterRevision != evidence.expectedRosterRevision()
                || transaction.timedSummons().find(
                evidence.expectedIdentity().profileId()
        ).isPresent()) {
            throw new IllegalStateException(
                    "capture_tame_command_source_mismatch"
            );
        }
    }
}
