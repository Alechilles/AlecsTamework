package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Executes exact-generation release rollback without retaining runtime or population state. */
final class ManagedCoopReleaseLifecycleRollbackService {
    private final ManagedCoopReleasePopulationCoordinator.LifecycleRollbackGateway gateway;
    private final LongSupplier clock;

    ManagedCoopReleaseLifecycleRollbackService(
            @Nonnull ManagedCoopReleasePopulationCoordinator.LifecycleRollbackGateway gateway,
            @Nonnull LongSupplier clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    CompletableFuture<Boolean> rollback(
            @Nonnull String operationId,
            long operationGeneration,
            @Nonnull String reason) {
        final CompletableFuture<MutationResult> completion;
        try {
            completion = gateway.failBeforeProjection(
                    operationId, operationGeneration, reason, clock.getAsLong());
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(false);
        }
        if (completion == null) {
            return CompletableFuture.completedFuture(false);
        }
        return completion.handle((result, failure) -> failure == null
                && result != null && result.succeeded());
    }
}
