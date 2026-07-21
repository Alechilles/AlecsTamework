package com.alechilles.alecstamework.provisioning;

import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Production journal adapter over the schema-v8 provisioning repository. */
public final class SqliteProvisioningOperationJournal implements ProvisioningOperationJournal {
    private final CompanionProvisioningRepository repository;

    public SqliteProvisioningOperationJournal(@Nonnull CompanionProvisioningRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public CompletionStage<CompanionProvisioningRepository.MutationResult> create(
            CompanionProvisioningOperationRecord operation) {
        return completion(repository.createAsync(Objects.requireNonNull(operation, "operation")));
    }

    @Override
    public CompletionStage<CompanionProvisioningRepository.MutationResult> advance(
            CompanionProvisioningRepository.AdvanceMutation mutation) {
        return completion(repository.advanceAsync(Objects.requireNonNull(mutation, "mutation")));
    }

    @Override
    public CompanionProvisioningOperationRecord find(String operationId) throws Exception {
        return repository.find(operationId);
    }

    @Override
    public CompanionProvisioningOperationRecord findByOrigin(
            String callerNamespace, String idempotencyKey) throws Exception {
        return repository.findByCallerKey(callerNamespace, idempotencyKey);
    }

    @Override
    public CompanionProvisioningOperationRecord findByProfile(String profileId) throws Exception {
        return repository.findByCanonicalProfile(profileId);
    }

    @Override
    public List<CompanionProvisioningOperationRecord> loadRecoverable(int limit) throws Exception {
        if (limit <= 0) return List.of();
        List<CompanionProvisioningOperationRecord> rows = repository.loadRecoverable();
        return rows.size() <= limit ? rows : List.copyOf(rows.subList(0, limit));
    }

    private static CompletionStage<CompanionProvisioningRepository.MutationResult> completion(
            PersistenceWriteQueue.WriteSubmission<CompanionProvisioningRepository.MutationResult> submission) {
        if (submission == null || !submission.accepted() || submission.completion() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("provisioning-journal-write-rejected"));
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome != null && outcome.isCommitted() && outcome.value() != null) {
                return CompletableFuture.completedFuture(outcome.value());
            }
            String reason = outcome == null || outcome.failureReason() == null
                    ? "provisioning-journal-write-failed" : outcome.failureReason();
            return CompletableFuture.failedFuture(new IllegalStateException(reason,
                    outcome == null ? null : outcome.failure()));
        });
    }
}
