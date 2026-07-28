package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Mutation-bound population API; callers must complete or cancel every reserved token.
 *
 * <p>Preparation, commit, and cancellation may perform durable persistence work and therefore
 * complete asynchronously. {@link #claimForApply(PopulationAdmissionToken)} is the only
 * synchronous stage; it performs in-memory/context revalidation and is safe to call immediately
 * before a world mutation.</p>
 */
public interface PopulationAdmissionApi {
    @Nonnull
    CompletionStage<PopulationAdmissionDecision> tryAdmit(@Nonnull PopulationAdmissionRequest request);

    /**
     * Role-aware admission used when population groups may apply. The default fails closed so an
     * API 0.8 implementation cannot silently bypass group capacity.
     */
    @Nonnull
    default CompletionStage<PopulationAdmissionDecision> tryAdmitV2(
            @Nonnull PopulationAdmissionRequestV2 request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        return CompletableFuture.completedFuture(
                PopulationAdmissionDecision.unavailable("population-admission-v2-authority-unavailable")
        );
    }

    @Nonnull
    CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(
            @Nonnull PopulationBatchAdmissionRequest request
    );

    @Nonnull
    PopulationAdmissionDecision claimForApply(@Nonnull PopulationAdmissionToken token);

    @Nonnull
    CompletionStage<PopulationAdmissionDecision> commit(@Nonnull PopulationAdmissionToken token);

    @Nonnull
    CompletionStage<PopulationAdmissionDecision> cancel(@Nonnull PopulationAdmissionToken token);

    /**
     * Asynchronously closes a bounded set of expired, unclaimed capabilities and their durable
     * journals. Implementations may also invoke this opportunistically before new preparations.
     */
    @Nonnull
    CompletionStage<Integer> cleanupExpired();

    /** Compatibility fallback used until a runtime admission coordinator is injected. */
    @Nonnull
    static PopulationAdmissionApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final String REASON = "population-admission-authority-unavailable";
        private static final PopulationAdmissionApi INSTANCE = new PopulationAdmissionApi() {
            @Override
            public CompletionStage<PopulationAdmissionDecision> tryAdmit(PopulationAdmissionRequest request) {
                if (request == null) {
                    throw new NullPointerException("request");
                }
                return CompletableFuture.completedFuture(PopulationAdmissionDecision.unavailable(REASON));
            }

            @Override
            public CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(
                    PopulationBatchAdmissionRequest request
            ) {
                if (request == null) {
                    throw new NullPointerException("request");
                }
                return CompletableFuture.completedFuture(
                        PopulationBatchAdmissionDecision.unavailable(request.units().size(), REASON)
                );
            }

            @Override
            public PopulationAdmissionDecision claimForApply(PopulationAdmissionToken token) {
                if (token == null) {
                    throw new NullPointerException("token");
                }
                return PopulationAdmissionDecision.unavailable(REASON);
            }

            @Override
            public CompletionStage<PopulationAdmissionDecision> commit(PopulationAdmissionToken token) {
                if (token == null) {
                    throw new NullPointerException("token");
                }
                return CompletableFuture.completedFuture(PopulationAdmissionDecision.unavailable(REASON));
            }

            @Override
            public CompletionStage<PopulationAdmissionDecision> cancel(PopulationAdmissionToken token) {
                if (token == null) {
                    throw new NullPointerException("token");
                }
                return CompletableFuture.completedFuture(PopulationAdmissionDecision.unavailable(REASON));
            }

            @Override
            public CompletionStage<Integer> cleanupExpired() {
                return CompletableFuture.completedFuture(0);
            }
        };

        private UnavailableHolder() {
        }
    }
}
