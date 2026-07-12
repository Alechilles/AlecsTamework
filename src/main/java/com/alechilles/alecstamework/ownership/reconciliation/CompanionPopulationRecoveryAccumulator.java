package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/** Collects startup operation-recovery outcomes while journals are processed serially. */
final class CompanionPopulationRecoveryAccumulator {
    private final List<CompanionPopulationOperationRecoveryService.AmbiguousOperation> ambiguous =
            new ArrayList<>();
    private int committed;
    private int retryable;
    private int canceled;

    void committed() {
        committed++;
    }

    void retryable() {
        retryable++;
    }

    void canceled() {
        canceled++;
    }

    void ambiguous(@Nonnull CompanionPopulationOperationRecord operation,
                   @Nonnull String reason) {
        ambiguous.add(new CompanionPopulationOperationRecoveryService.AmbiguousOperation(
                operation.operationId(), operation.profileId(), reason
        ));
    }

    @Nonnull
    CompanionPopulationOperationRecoveryService.RecoveryResult freeze() {
        return new CompanionPopulationOperationRecoveryService.RecoveryResult(
                committed, retryable, canceled, List.copyOf(ambiguous)
        );
    }
}
