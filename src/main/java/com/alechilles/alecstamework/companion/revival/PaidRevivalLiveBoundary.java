package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Idempotent external boundary for exact inventory charge plus companion spawn.
 *
 * <p>Implementations may return no-charge or refund-required only from positive receipt
 * evidence, never from observed absence.</p>
 */
@FunctionalInterface
public interface PaidRevivalLiveBoundary {
    @Nonnull
    CompletionStage<PaidRevivalLiveResult> applyOrResolve(
            @Nonnull PaidRevivalRequest revival,
            @Nonnull OperationEnvelope operation
    );
}
