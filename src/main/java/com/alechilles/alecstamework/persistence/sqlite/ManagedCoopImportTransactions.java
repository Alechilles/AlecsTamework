package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.BeginSessionRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionTransactionHook;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.FinalizationRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationProof;

/** Coordinates focused session and source transactions without owning their SQL details. */
final class ManagedCoopImportTransactions {
    private final ManagedCoopImportSourceTransactions sources =
            new ManagedCoopImportSourceTransactions();
    private final ManagedCoopImportSessionTransactions sessions =
            new ManagedCoopImportSessionTransactions(sources);

    MutationResult begin(Connection connection, BeginSessionRequest request) throws SQLException {
        return sessions.begin(connection, request);
    }

    MutationResult bindDispositionAtomically(Connection connection,
                                              DispositionBinding binding,
                                              DispositionTransactionHook hook) throws Exception {
        MutationResult preflight = sources.preflightDisposition(connection, binding);
        if (preflight != null) {
            return preflight;
        }
        Savepoint boundary = connection.setSavepoint();
        try {
            hook.write(connection, binding);
            MutationResult result = sources.bindDisposition(connection, binding);
            if (result.status() != ManagedCoopImportRepository.MutationStatus.APPLIED) {
                connection.rollback(boundary);
            }
            connection.releaseSavepoint(boundary);
            return result;
        } catch (Exception exception) {
            rollback(connection, boundary, exception);
            throw exception;
        }
    }

    MutationResult recordNeutralization(Connection connection, NeutralizationProof proof)
            throws SQLException {
        return sources.recordNeutralization(connection, proof);
    }

    MutationResult refreshNeutralization(Connection connection, NeutralizationProof proof)
            throws SQLException {
        return sources.refreshNeutralization(connection, proof);
    }

    MutationResult finalizeAuthority(Connection connection, FinalizationRequest request)
            throws SQLException {
        return sessions.finalizeAuthority(connection, request);
    }

    private void rollback(Connection connection, Savepoint boundary, Exception original) {
        try {
            connection.rollback(boundary);
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
        try {
            connection.releaseSavepoint(boundary);
        } catch (SQLException releaseFailure) {
            original.addSuppressed(releaseFailure);
        }
    }
}
