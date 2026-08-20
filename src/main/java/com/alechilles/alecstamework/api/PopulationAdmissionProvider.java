package com.alechilles.alecstamework.api;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Evaluates one immutable population-admission request against an external policy snapshot. */
@FunctionalInterface
public interface PopulationAdmissionProvider {
    /** Returns an allow, deny, or unavailable decision without mutating Tamework state. */
    @Nonnull
    CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull PopulationAdmissionProviderRequest request
    );
}
