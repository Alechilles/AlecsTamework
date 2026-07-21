package com.alechilles.alecstamework.items.capturepolicy.runtime;

import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Correlates a resolved capture with its durable population mutation during restart recovery. */
@FunctionalInterface
public interface CaptureAttemptRecoveryEvidence {
    @Nonnull
    Evidence inspect(@Nonnull CaptureAttemptRecord attempt) throws Exception;

    @Nonnull
    static CaptureAttemptRecoveryEvidence unavailable() {
        return ignored -> new Evidence(Status.UNAVAILABLE, "capture-recovery-evidence-unavailable");
    }

    enum Status {
        COMMITTED,
        COMPENSATED,
        RESUMABLE,
        CONFLICT,
        UNAVAILABLE
    }

    record Evidence(@Nonnull Status status, @Nonnull String reason) {
        public Evidence {
            status = Objects.requireNonNull(status, "status");
            reason = Objects.requireNonNull(reason, "reason").trim();
            if (reason.isEmpty()) throw new IllegalArgumentException("reason is required");
        }
    }
}
