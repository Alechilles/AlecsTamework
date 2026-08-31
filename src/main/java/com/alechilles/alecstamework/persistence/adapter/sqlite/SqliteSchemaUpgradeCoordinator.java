package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceSchemaStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Resolves one schema migration and its exact post-migration verification. */
final class SqliteSchemaUpgradeCoordinator {
    private SqliteSchemaUpgradeCoordinator() {
    }

    static PersistenceTransactionResult<PersistenceSchemaStatus> run(
            Migration migration,
            Supplier<PersistenceReadResult<PersistenceSchemaStatus>> verifier,
            BiFunction<Throwable, String, StorageFailure> failureClassifier,
            String verificationCode,
            String operation
    ) {
        try {
            migration.migrate();
        } catch (Exception failure) {
            PersistenceReadResult<PersistenceSchemaStatus> afterFailure =
                    verifier.get();
            if (afterFailure instanceof
                    PersistenceReadResult.Found<PersistenceSchemaStatus> found) {
                return new PersistenceTransactionResult.Committed<>(found.value());
            }
            if (failure instanceof OutcomeUnknownException) {
                return new PersistenceTransactionResult.Unknown<>(
                        new StorageFailure(
                                StorageFailureKind.UNKNOWN,
                                "schema_upgrade_outcome_unknown",
                                operation,
                                false,
                                failure
                        )
                );
            }
            return new PersistenceTransactionResult.RolledBack<>(
                    failureClassifier.apply(failure, operation)
            );
        }
        PersistenceReadResult<PersistenceSchemaStatus> verified = verifier.get();
        if (verified instanceof
                PersistenceReadResult.Found<PersistenceSchemaStatus> found) {
            return new PersistenceTransactionResult.Committed<>(found.value());
        }
        Throwable cause = verified instanceof PersistenceReadResult.Failed<
                PersistenceSchemaStatus> failed
                ? failed.failure().cause()
                : null;
        return new PersistenceTransactionResult.Unknown<>(
                new StorageFailure(
                        StorageFailureKind.UNKNOWN,
                        verificationCode,
                        operation,
                        false,
                        cause
                )
        );
    }

    @FunctionalInterface
    interface Migration {
        void migrate() throws Exception;
    }

    static final class OutcomeUnknownException extends Exception {
        OutcomeUnknownException(Throwable cause) {
            super("schema_upgrade_outcome_unknown", cause);
        }
    }
}
