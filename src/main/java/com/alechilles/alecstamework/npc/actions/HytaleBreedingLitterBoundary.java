package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.internal.ManagedBatchAdmissionAuthority;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Resolves the current named world before one durable litter attempt. */
public final class HytaleBreedingLitterBoundary
        implements BreedingLitterLiveBoundary {
    private final Supplier<ManagedBatchAdmissionAuthority> admissions;
    private final BreedingLitterWorldExecutor executor;

    public HytaleBreedingLitterBoundary(
            @Nonnull Supplier<ManagedBatchAdmissionAuthority> admissions
    ) {
        this(admissions, new BreedingLitterWorldExecutor());
    }

    HytaleBreedingLitterBoundary(
            Supplier<ManagedBatchAdmissionAuthority> admissions,
            BreedingLitterWorldExecutor executor
    ) {
        this.admissions = Objects.requireNonNull(
                admissions, "admissions"
        );
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    @Nonnull
    public CompletionStage<BreedingLitterLiveResult> reconcileAndSpawn(
            @Nonnull BreedingLitterOperation litter,
            @Nonnull OperationEnvelope operation
    ) {
        if (litter == null || operation == null) {
            return retry("breeding_litter_request_invalid", null);
        }
        World scheduled;
        try {
            scheduled = Universe.get().getWorld(litter.worldName());
        } catch (RuntimeException failure) {
            return retry("breeding_litter_world_unavailable", failure);
        }
        if (scheduled == null || !scheduled.isAlive()) {
            return retry("breeding_litter_world_unavailable", null);
        }
        CompletableFuture<BreedingLitterLiveResult> result =
                new CompletableFuture<>();
        try {
            scheduled.execute(() -> runOnWorld(
                    scheduled, litter, operation, result
            ));
        } catch (RuntimeException failure) {
            result.complete(BreedingLitterLiveResult.retryable(
                    "breeding_litter_world_dispatch_failed", failure
            ));
        }
        return result;
    }

    private void runOnWorld(
            World scheduled,
            BreedingLitterOperation litter,
            OperationEnvelope operation,
            CompletableFuture<BreedingLitterLiveResult> result
    ) {
        try {
            World current = Universe.get().getWorld(litter.worldName());
            ManagedBatchAdmissionAuthority authority = admissions.get();
            if (current == null || current != scheduled
                    || !current.isAlive()
                    || current.getEntityStore() == null
                    || authority == null) {
                result.complete(BreedingLitterLiveResult.retryable(
                        "breeding_litter_world_context_changed", null
                ));
                return;
            }
            Store<EntityStore> store = current.getEntityStore().getStore();
            CompletionStage<BreedingLitterLiveResult> stage = executor.execute(
                    current, store, litter, operation, authority
            );
            if (stage == null) {
                result.complete(BreedingLitterLiveResult.retryable(
                        "breeding_litter_executor_returned_null", null
                ));
                return;
            }
            stage.whenComplete((value, failure) -> result.complete(
                    failure == null && value != null
                            ? value
                            : BreedingLitterLiveResult.retryable(
                                    "breeding_litter_executor_failed",
                                    failure
                            )
            ));
        } catch (RuntimeException | LinkageError failure) {
            result.complete(BreedingLitterLiveResult.retryable(
                    "breeding_litter_world_failed", failure
            ));
        }
    }

    private static CompletionStage<BreedingLitterLiveResult> retry(
            String code,
            Throwable failure
    ) {
        return BreedingLitterLiveResult.retryable(
                code, failure
        ).completed();
    }
}
