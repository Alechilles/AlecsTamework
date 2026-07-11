package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Mutation-bound population API; callers must complete or cancel every reserved token. */
public interface PopulationAdmissionApi {
    @Nonnull
    PopulationAdmissionDecision tryAdmit(@Nonnull PopulationAdmissionRequest request);

    @Nonnull
    PopulationAdmissionDecision claimForApply(@Nonnull PopulationAdmissionToken token);

    @Nonnull
    PopulationAdmissionDecision commit(@Nonnull PopulationAdmissionToken token);

    @Nonnull
    PopulationAdmissionDecision cancel(@Nonnull PopulationAdmissionToken token);

    /** Compatibility fallback used until a runtime admission coordinator is injected. */
    @Nonnull
    static PopulationAdmissionApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final String REASON = "population-admission-authority-unavailable";
        private static final PopulationAdmissionApi INSTANCE = new PopulationAdmissionApi() {
            @Override
            public PopulationAdmissionDecision tryAdmit(PopulationAdmissionRequest request) {
                if (request == null) {
                    throw new NullPointerException("request");
                }
                return PopulationAdmissionDecision.unavailable(REASON);
            }

            @Override
            public PopulationAdmissionDecision claimForApply(PopulationAdmissionToken token) {
                if (token == null) {
                    throw new NullPointerException("token");
                }
                return PopulationAdmissionDecision.unavailable(REASON);
            }

            @Override
            public PopulationAdmissionDecision commit(PopulationAdmissionToken token) {
                if (token == null) {
                    throw new NullPointerException("token");
                }
                return PopulationAdmissionDecision.unavailable(REASON);
            }

            @Override
            public PopulationAdmissionDecision cancel(PopulationAdmissionToken token) {
                if (token == null) {
                    throw new NullPointerException("token");
                }
                return PopulationAdmissionDecision.unavailable(REASON);
            }
        };

        private UnavailableHolder() {
        }
    }
}
