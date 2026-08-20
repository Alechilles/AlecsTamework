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
 */
@FunctionalInterface
public interface PopulationAdmissionProvider {
    /** Returns an allow, deny, or unavailable decision without mutating Tamework state. */
    @Nonnull
    CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull PopulationAdmissionProviderRequest request
    );
}
