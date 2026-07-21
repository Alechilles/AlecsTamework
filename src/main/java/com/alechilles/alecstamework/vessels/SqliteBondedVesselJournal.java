package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Production journal adapter over the schema-v8 SQLite bonded-vessel repository. */
public final class SqliteBondedVesselJournal implements BondedVesselJournal {
    private final BondedVesselRepository repository;

    public SqliteBondedVesselJournal(@Nonnull BondedVesselRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public BondedVesselBindingRecord findBinding(String bindingId) throws Exception {
        return repository.findBinding(bindingId);
    }

    @Override
    public BondedVesselBindingRecord findBindingByProfile(String profileId) throws Exception {
        return repository.findBindingByProfile(profileId);
    }

    @Override
    public BondedVesselOperationRecord findOperation(String operationId) throws Exception {
        return repository.findOperation(operationId);
    }

    @Override
    public BondedVesselOperationRecord findOperationByOrigin(
            String callerNamespace,
            String idempotencyKey
    ) throws Exception {
        return repository.findOperationByCallerKey(callerNamespace, idempotencyKey);
    }

    @Override
    public List<BondedVesselOperationRecord> loadRecoverable(int limit) throws Exception {
        if (limit <= 0) {
            return List.of();
        }
        List<BondedVesselOperationRecord> operations = repository.loadRecoverableOperations();
        List<BondedVesselOperationRecord> publicTransitions = operations.stream()
                .filter(operation -> operation.action() != BondedVesselOperationRecord.Action.INITIAL_BIND)
                .filter(operation -> operation.action() != BondedVesselOperationRecord.Action.MARK_DEAD)
                .filter(operation -> operation.action() != BondedVesselOperationRecord.Action.MARK_LOST)
                .toList();
        return publicTransitions.size() <= limit ? publicTransitions
                : List.copyOf(publicTransitions.subList(0, limit));
    }

    @Override
    public CompletionStage<BondedVesselRepository.MutationResult> prepare(
            BondedVesselOperationRecord operation
    ) {
        return bridge(repository.prepareTransitionAsync(operation));
    }

    @Override
    public CompletionStage<BondedVesselRepository.MutationResult> claim(String operationId, long nowMs) {
        return bridge(repository.claimForApplyAsync(operationId, nowMs));
    }

    @Override
    public CompletionStage<BondedVesselRepository.MutationResult> apply(
            BondedVesselRepository.AppliedTransition transition
    ) {
        return bridge(repository.applyAsync(transition));
    }

    @Override
    public CompletionStage<BondedVesselRepository.MutationResult> commit(String operationId, long nowMs) {
        return bridge(repository.commitAsync(operationId, nowMs));
    }

    @Override
    public CompletionStage<BondedVesselRepository.MutationResult> cancel(
            String operationId,
            String reason,
            long nowMs
    ) {
        return bridge(repository.cancelPreparedAsync(operationId, reason, nowMs));
    }

    @Override
    public CompletionStage<BondedVesselRepository.MutationResult> denyBeforeApply(
            String operationId,
            String reason,
            BondedVesselRepository.ApplyAbsenceProof proof,
            long nowMs
    ) {
        return bridge(repository.denyBeforeApplyAsync(operationId, reason, proof, nowMs));
    }

    @Override
    public CompletionStage<BondedVesselRepository.MutationResult> quarantine(
            String operationId,
            String reason,
            long nowMs
    ) {
        return bridge(repository.quarantineAsync(operationId, reason, nowMs));
    }

    @Nonnull
    private static <T> CompletionStage<T> bridge(
            @Nonnull PersistenceWriteQueue.WriteSubmission<T> submission
    ) {
        if (!submission.accepted()) {
            return CompletableFuture.failedFuture(
                    new JournalUnavailableException("bonded-vessel-journal-write-rejected")
            );
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome.isCommitted() && outcome.value() != null) {
                return CompletableFuture.completedFuture(outcome.value());
            }
            Throwable cause = outcome.failure();
            if (cause == null) {
                cause = new JournalUnavailableException(
                        outcome.failureReason() == null
                                ? "bonded-vessel-journal-write-failed"
                                : outcome.failureReason()
                );
            }
            return CompletableFuture.failedFuture(cause);
        });
    }

    public static final class JournalUnavailableException extends RuntimeException {
        public JournalUnavailableException(@Nonnull String message) {
            super(message);
        }
    }
}
