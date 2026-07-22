package com.alechilles.alecstamework.items.capturepolicy.runtime;

import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Uses the canonical population journal to converge crash-interrupted capture attempts. */
public final class SqliteCaptureAttemptRecoveryEvidence implements CaptureAttemptRecoveryEvidence {
    private final CompanionPopulationRepository population;

    public SqliteCaptureAttemptRecoveryEvidence(@Nonnull CompanionPopulationRepository population) {
        this.population = Objects.requireNonNull(population, "population");
    }

    @Override
    @Nonnull
    public Evidence inspect(@Nonnull CaptureAttemptRecord attempt) throws Exception {
        Objects.requireNonNull(attempt, "attempt");
        String operationId = attempt.populationOperationId();
        if (operationId == null || operationId.isBlank()) {
            return new Evidence(Status.CONFLICT, "capture-recovery-population-operation-missing");
        }
        CompanionPopulationOperationRecord operation = population.findOperation(operationId);
        if (operation == null) {
            return new Evidence(Status.CONFLICT, "capture-recovery-population-row-missing");
        }
        String profileId = attempt.identity().profileId();
        if (profileId == null || !profileId.equals(operation.profileId())) {
            return new Evidence(Status.CONFLICT, "capture-recovery-population-profile-conflict");
        }
        if (attempt.config().successDisposition()
                == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK) {
            // Population evidence alone cannot prove or compensate the coupled roster/lease
            // contract. Player-join convergence either replays those durable writes or creates
            // the exact source refund while the population operation is still provably unapplied.
            return new Evidence(Status.RESUMABLE,
                    "capture-recovery-tame-link-convergence-required");
        }
        return switch (operation.state()) {
            case COMMITTED -> new Evidence(
                    Status.COMMITTED, "capture-recovery-population-committed");
            case RETRYABLE, FAILED -> new Evidence(
                    Status.COMPENSATED, "capture-recovery-population-" + operation.state().name().toLowerCase());
            case PREPARED, APPLYING, APPLIED, COMPENSATING -> new Evidence(
                    Status.RESUMABLE, "capture-recovery-population-" + operation.state().name().toLowerCase());
        };
    }
}
