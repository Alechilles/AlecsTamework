package com.alechilles.alecstamework.api;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Evaluates one immutable population-admission request against an external policy snapshot.
 *
 * <p>Evaluation can run on any platform executor. It has no game-loop or thread-affinity
 * guarantee, and it must not depend on thread-local game state. A synchronous throw, null stage
 * or decision, exceptional completion, or completion after the coordinator's bounded timeout is
 * translated to {@link PopulationAdmissionProviderStatus#UNAVAILABLE}. Tamework then fails the
 * managed admission closed.
 *
 * <p>Tamework may invoke one registered provider for more than one request at the same time.
 * Request delivery order and completion order have no guarantee. Implementations must be
 * thread-safe and must base each decision only on the immutable request and an external policy
 * snapshot.
 */
@FunctionalInterface
public interface PopulationAdmissionProvider {
    /** Returns an allow, deny, or unavailable decision without mutating Tamework state. */
    @Nonnull
    CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull PopulationAdmissionProviderRequest request
    );
}
