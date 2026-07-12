package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopPopulationMutationContext.ParsedMutation;
import java.sql.Connection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies one exact schema-v5 managed-coop mutation inside the population commit transaction. */
final class CompanionPopulationManagedCoopMutation {
    private CompanionPopulationManagedCoopMutation() {
    }

    static void applyIfPresent(@Nonnull Connection connection,
                               @Nonnull CoopLifecycleOperationRepository repository,
                               @Nullable String targetContextJson) throws Exception {
        ParsedMutation mutation = ManagedCoopPopulationMutationContext.parse(targetContextJson);
        if (mutation == null) {
            return;
        }
        if (mutation.capture() != null) {
            MutationResult result = repository.claimCaptureInTransaction(
                    connection, mutation.capture()
            );
            require(result, OperationState.SLOT_COMMITTED, "capture");
            return;
        }
        if (mutation.release() == null) {
            throw new IllegalArgumentException("Managed-coop population mutation has no mode payload.");
        }
        MutationResult result = repository.commitPopulationReleaseInTransaction(
                connection, mutation.release()
        );
        require(result, OperationState.FINALIZED, "release");
    }

    private static void require(@Nullable MutationResult result,
                                @Nonnull OperationState requiredState,
                                @Nonnull String stage) {
        if (result == null || !result.succeeded() || result.operation() == null
                || result.operation().state() != requiredState) {
            String detail = result == null ? "result_missing" : result.detail();
            throw new IllegalStateException(
                    "Managed-coop population " + stage + " failed: "
                            + (detail == null || detail.isBlank() ? "state_mismatch" : detail)
            );
        }
    }
}
