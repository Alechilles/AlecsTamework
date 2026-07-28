package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import javax.annotation.Nonnull;

/** Resolves the actor-local live boundary for one captured-item coop operation. */
@FunctionalInterface
public interface CompanionCoopCapturedItemAttemptFactory {
    @Nonnull
    CompanionCoopCapturedItemAttempt open(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope operation
    );
}
