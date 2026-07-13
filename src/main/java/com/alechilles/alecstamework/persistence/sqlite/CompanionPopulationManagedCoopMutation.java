package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopPopulationMutationContext.ParsedMutation;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.sql.Connection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies one exact schema-v5 managed-coop mutation inside the population commit transaction. */
final class CompanionPopulationManagedCoopMutation {
    private CompanionPopulationManagedCoopMutation() {
    }

    static ApplyResult applyIfPresent(@Nonnull Connection connection,
                                      @Nonnull CoopLifecycleOperationRepository repository,
                                      @Nullable String targetContextJson) throws Exception {
        ParsedMutation mutation = ManagedCoopPopulationMutationContext.parse(targetContextJson);
        if (mutation == null) {
            return ApplyResult.success();
        }
        if (mutation.capture() != null) {
            MutationResult result = repository.claimCaptureInTransaction(
                    connection, mutation.capture()
            );
            return require(result, OperationState.SLOT_COMMITTED, "capture");
        }
        if (mutation.release() != null) {
            MutationResult result = repository.commitPopulationReleaseInTransaction(
                    connection, mutation.release()
            );
            return require(result, OperationState.FINALIZED, "release");
        }
        if (mutation.detach() != null) {
            ManagedCoopResidentRepository.MutationResult result =
                    repository.detachPopulationResidentInTransaction(
                            connection, mutation.detach()
                    );
            return requireDetached(result);
        }
        throw new IllegalArgumentException("Managed-coop population mutation has no mode payload.");
    }

    @Nonnull
    private static ApplyResult require(@Nullable MutationResult result,
                                       @Nonnull OperationState requiredState,
                                       @Nonnull String stage) {
        if (result == null || !result.succeeded() || result.operation() == null
                || result.operation().state() != requiredState) {
            String detail = result == null ? "result_missing" : result.detail();
            return ApplyResult.conflict(stage, detail);
        }
        return ApplyResult.success();
    }

    @Nonnull
    private static ApplyResult requireDetached(
            @Nullable ManagedCoopResidentRepository.MutationResult result) {
        ResidentRecord resident = result == null ? null : result.resident();
        if (result == null || !result.succeeded() || resident == null
                || resident.active()
                || resident.state() != ManagedCoopResidentRepository.ResidentState.RETIRED) {
            return ApplyResult.conflict(
                    "detach",
                    result == null ? "result_missing" : result.detail()
            );
        }
        return ApplyResult.success();
    }

    record ApplyResult(boolean applied, @Nullable String detail) {
        @Nonnull
        private static ApplyResult success() {
            return new ApplyResult(true, null);
        }

        @Nonnull
        private static ApplyResult conflict(@Nonnull String stage, @Nullable String detail) {
            String reason = detail == null || detail.isBlank() ? "state_mismatch" : detail;
            return new ApplyResult(false, "managed_coop_" + stage + "_conflict:" + reason);
        }
    }
}
