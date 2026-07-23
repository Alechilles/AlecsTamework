package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.compensation.PreparedCompensationDetail;
import com.alechilles.alecstamework.persistence.compensation.TimedCompensatedOperationWork;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import javax.annotation.Nonnull;

/**
 * Focused transaction engine for the compensation edges in the shared operation protocol.
 *
 * <p>This class owns no feature decisions or live effects. It only makes typed compensation
 * evidence atomic with the canonical shared phase transitions.</p>
 */
final class SqliteOperationCompensationEngine {
    private static final PersistenceReadKind COMPENSATION_READBACK =
            new PersistenceReadKind("operation_compensation_readback");
    private static final PersistenceReadKind COMPENSATED_READBACK =
            new PersistenceReadKind("operation_compensated_readback");

    private final SqliteUnitOfWorkRunner units;

    SqliteOperationCompensationEngine(
            @Nonnull SqliteUnitOfWorkRunner units
    ) {
        if (units == null) {
            throw new IllegalArgumentException(
                    "Compensation unit-of-work runner is required"
            );
        }
        this.units = units;
    }

    @Nonnull
    SqliteUnitOfWorkRunner.Submission<OperationEnvelope> begin(
            @Nonnull OperationEnvelope expected,
            @Nonnull PreparedCompensationDetail detail,
            long preparedAtMs
    ) {
        requireCompensable(expected, detail);
        SqliteTransactionCommand<OperationEnvelope> command =
                new SqliteTransactionCommand<>(
                        expected.operationId(),
                        expected.kind(),
                        TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                        connection -> beginTransaction(
                                connection,
                                expected,
                                detail,
                                preparedAtMs
                        )
                );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                COMPENSATION_READBACK,
                connection -> exactPreparingReadback(
                        connection,
                        expected,
                        detail
                )
        ));
    }

    @Nonnull
    <T> SqliteUnitOfWorkRunner.Submission<OperationEnvelope> commit(
            @Nonnull OperationEnvelope expected,
            @Nonnull T payload,
            @Nonnull String liveEvidence,
            @Nonnull TimedCompensatedOperationWork<T> work,
            long compensatedAtMs
    ) {
        if (expected == null || payload == null || liveEvidence == null
                || liveEvidence.isBlank() || work == null) {
            throw new IllegalArgumentException(
                    "Complete compensated operation evidence is required"
            );
        }
        if (expected.phase() != OperationPhase.COMPENSATING) {
            throw new IllegalArgumentException(
                    "Compensated commit requires compensating phase"
            );
        }
        String evidence = liveEvidence.trim();
        SqliteTransactionCommand<OperationEnvelope> command =
                new SqliteTransactionCommand<>(
                        expected.operationId(),
                        expected.kind(),
                        TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                        connection -> commitTransaction(
                                connection,
                                expected,
                                payload,
                                evidence,
                                work,
                                compensatedAtMs
                        )
                );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                COMPENSATED_READBACK,
                connection -> exactCommittedReadback(
                        connection,
                        expected,
                        payload,
                        work
                )
        ));
    }

    private OperationEnvelope beginTransaction(
            java.sql.Connection connection,
            OperationEnvelope expected,
            PreparedCompensationDetail detail,
            long preparedAtMs
    ) throws Exception {
        SqlitePersistenceTransactionContext transaction =
                new SqlitePersistenceTransactionContext(connection);
        OperationEnvelope current = current(transaction, expected);
        if (!detail.matches(transaction, current)) {
            detail.prepare(transaction, current, preparedAtMs);
        }
        if (!detail.matches(transaction, current)) {
            throw new IllegalStateException(
                    "operation_compensation_detail_missing"
            );
        }
        return requireApplied(
                transaction.operations().transition(new OperationTransition(
                        current.operationId(),
                        current.phase(),
                        OperationPhase.COMPENSATING,
                        current.leaseOwner(),
                        null,
                        null,
                        preparedAtMs
                )),
                "operation_compensating_transition"
        );
    }

    private <T> OperationEnvelope commitTransaction(
            java.sql.Connection connection,
            OperationEnvelope expected,
            T payload,
            String liveEvidence,
            TimedCompensatedOperationWork<T> work,
            long compensatedAtMs
    ) throws Exception {
        SqlitePersistenceTransactionContext transaction =
                new SqlitePersistenceTransactionContext(connection);
        OperationEnvelope current = current(transaction, expected);
        work.execute(
                transaction,
                current,
                payload,
                liveEvidence,
                compensatedAtMs
        );
        if (!work.matches(transaction, current, payload)) {
            throw new IllegalStateException(
                    "operation_compensated_evidence_missing"
            );
        }
        return requireApplied(
                transaction.operations().transition(new OperationTransition(
                        current.operationId(),
                        current.phase(),
                        OperationPhase.COMPENSATED,
                        current.leaseOwner(),
                        null,
                        null,
                        compensatedAtMs
                )),
                "operation_compensated_transition"
        );
    }

    private PersistenceReadResult<OperationEnvelope> exactPreparingReadback(
            java.sql.Connection connection,
            OperationEnvelope expected,
            PreparedCompensationDetail detail
    ) throws Exception {
        SqlitePersistenceTransactionContext transaction =
                new SqlitePersistenceTransactionContext(connection);
        OperationEnvelope operation = transaction.operations()
                .find(expected.operationId())
                .orElse(null);
        if (operation != null
                && operation.phase() == OperationPhase.COMPENSATING
                && detail.matches(transaction, operation)) {
            return found(operation);
        }
        return PersistenceReadResult.absent();
    }

    private <T> PersistenceReadResult<OperationEnvelope> exactCommittedReadback(
            java.sql.Connection connection,
            OperationEnvelope expected,
            T payload,
            TimedCompensatedOperationWork<T> work
    ) throws Exception {
        SqlitePersistenceTransactionContext transaction =
                new SqlitePersistenceTransactionContext(connection);
        OperationEnvelope operation = transaction.operations()
                .find(expected.operationId())
                .orElse(null);
        if (operation != null
                && operation.phase() == OperationPhase.COMPENSATED
                && work.matches(transaction, operation, payload)) {
            return found(operation);
        }
        return PersistenceReadResult.absent();
    }

    private OperationEnvelope current(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope expected
    ) {
        OperationEnvelope current = transaction.operations()
                .find(expected.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "operation_not_found"
                ));
        if (current.phase() != expected.phase()
                || !java.util.Objects.equals(
                        current.leaseOwner(),
                        expected.leaseOwner()
                )) {
            throw new IllegalStateException(
                    "operation_phase_or_lease_mismatch"
            );
        }
        return current;
    }

    private void requireCompensable(
            OperationEnvelope expected,
            PreparedCompensationDetail detail
    ) {
        if (expected == null || detail == null) {
            throw new IllegalArgumentException(
                    "Expected operation and compensation detail are required"
            );
        }
        if (expected.phase() != OperationPhase.LIVE_APPLYING
                && expected.phase() != OperationPhase.RETRYABLE
                && expected.phase() != OperationPhase.UNKNOWN) {
            throw new IllegalArgumentException(
                    "Compensation requires applying, retryable, or unknown phase"
            );
        }
    }

    private PersistenceReadResult<OperationEnvelope> found(
            OperationEnvelope operation
    ) {
        return PersistenceReadResult.found(
                operation,
                operation.attemptCount()
        );
    }

    private <T> T requireApplied(
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
