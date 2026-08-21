package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationReader;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Small read and transaction-result adapters kept outside the operation core. */
final class PopulationDomainAdmissionOperationSupport {
    private PopulationDomainAdmissionOperationSupport() {
    }

    static CompletionStage<Optional<OperationEnvelope>> findByIdempotency(
            SqliteOperationReader reader,
            IdempotencyKey idempotencyKey
    ) {
        return reader.findByIdempotency(
                PopulationDomainAdmissionDefinition.KIND, idempotencyKey
        ).thenApply(result -> {
            if (result instanceof PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel> found) {
                return Optional.of(found.value().operation());
            }
            if (result instanceof PersistenceReadResult.Absent<SqliteOperationReader.OperationReadModel>) {
                return Optional.empty();
            }
            if (result instanceof PersistenceReadResult.Failed<SqliteOperationReader.OperationReadModel> failed) {
                throw new IllegalStateException(failed.failure().code(), failed.failure().cause());
            }
            throw new IllegalStateException("domain_admission_idempotency_read_invalid");
        });
    }

    static CompletionStage<OperationEnvelope> read(
            SqliteOperationReader reader,
            OperationId operationId
    ) {
        return reader.find(operationId).thenCompose(result -> {
            if (result instanceof PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel> found) {
                return CompletableFuture.completedFuture(found.value().operation());
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("domain_admission_operation_missing")
            );
        });
    }

    static CompletionStage<Boolean> litterJobExists(
            SqliteOperationReader reader,
            OperationId litterId
    ) {
        UUID jobId = UUID.nameUUIDFromBytes((litterId.value()
                + ":breeding-litter-job").getBytes(StandardCharsets.UTF_8));
        return reader.find(new OperationId(jobId)).thenApply(result -> {
            if (result instanceof PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel> found) {
                OperationEnvelope job = found.value().operation();
                return "breeding_litter".equals(job.kind().value())
                        && !job.phase().isTerminal();
            }
            if (result instanceof PersistenceReadResult.Failed<SqliteOperationReader.OperationReadModel> failed) {
                throw new IllegalStateException(
                        "domain_admission_litter_job_read_failed",
                        failed.failure().cause()
                );
            }
            return false;
        });
    }

    static CompletionStage<OperationEnvelope> committedValue(
            PersistenceTransactionResult<OperationEnvelope> result,
            String failureCode
    ) {
        if (result instanceof PersistenceTransactionResult.Committed<OperationEnvelope> committed
                && committed.value() != null) {
            return CompletableFuture.completedFuture(committed.value());
        }
        return CompletableFuture.failedFuture(new IllegalStateException(failureCode));
    }
}
