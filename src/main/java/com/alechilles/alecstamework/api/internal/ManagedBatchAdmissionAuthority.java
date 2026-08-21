package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Internal aggregate admission seam for managed newborn batches.
 *
 * <p>This authority is deliberately separate from the public single-unit
 * admission API. It lets runtime code reserve and settle one durable litter
 * operation without depending on a concrete facade implementation.</p>
 */
public interface ManagedBatchAdmissionAuthority {
    /** Prepares one aggregate admission with frozen child ordinals. */
    @Nonnull
    CompletionStage<PopulationAdmissionDecision> prepareManagedBatch(
            @Nonnull ManagedBatchAdmissionRequest request
    );

    /** Claims one prepared aggregate immediately before live mutation. */
    @Nonnull
    PopulationAdmissionDecision claimManagedBatch(
            @Nonnull PopulationAdmissionToken token
    );

    /** Authenticates a durable batch before a restarted world mutation. */
    @Nonnull
    CompletionStage<PopulationAdmissionDecision> claimManagedBatchForRecovery(
            @Nonnull PopulationAdmissionToken token
    );

    /** Settles exact live child ordinals and their actual UUID receipts. */
    @Nonnull
    CompletionStage<ManagedBatchSettlement> settleManagedBatch(
            @Nonnull PopulationAdmissionToken token,
            @Nonnull Set<Integer> settledOrdinals,
            @Nonnull Map<Integer, UUID> actualChildIds
    );

    /** Returns a fail-closed seam for degraded compositions. */
    @Nonnull
    static ManagedBatchAdmissionAuthority unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation used when restored persistence is absent. */
    final class UnavailableHolder {
        private static final ManagedBatchAdmissionAuthority INSTANCE =
                new ManagedBatchAdmissionAuthority() {
                    @Override
                    public CompletionStage<PopulationAdmissionDecision>
                    prepareManagedBatch(ManagedBatchAdmissionRequest request) {
                        if (request == null) {
                            throw new NullPointerException("request");
                        }
                        return CompletableFuture.completedFuture(
                                PopulationAdmissionDecision.unavailable(
                                        "population-admission-batch-authority-unavailable"
                                )
                        );
                    }

                    @Override
                    public PopulationAdmissionDecision claimManagedBatch(
                            PopulationAdmissionToken token
                    ) {
                        if (token == null) {
                            throw new NullPointerException("token");
                        }
                        return PopulationAdmissionDecision.unavailable(
                                "population-admission-batch-authority-unavailable"
                        );
                    }

                    @Override
                    public CompletionStage<PopulationAdmissionDecision>
                    claimManagedBatchForRecovery(PopulationAdmissionToken token) {
                        if (token == null) {
                            throw new NullPointerException("token");
                        }
                        return CompletableFuture.completedFuture(
                                PopulationAdmissionDecision.unavailable(
                                        "population-admission-batch-authority-unavailable"
                                )
                        );
                    }

                    @Override
                    public CompletionStage<ManagedBatchSettlement>
                    settleManagedBatch(
                            PopulationAdmissionToken token,
                            Set<Integer> settledOrdinals,
                            Map<Integer, UUID> actualChildIds
                    ) {
                        if (token == null || settledOrdinals == null
                                || actualChildIds == null) {
                            throw new NullPointerException("batch settlement");
                        }
                        return CompletableFuture.completedFuture(
                                new ManagedBatchSettlement(
                                        ManagedBatchSettlement.Status.UNAVAILABLE,
                                        "population-admission-batch-authority-unavailable",
                                        1,
                                        Set.of(),
                                        Map.of()
                                )
                        );
                    }
                };

        private UnavailableHolder() {
        }
    }
}
