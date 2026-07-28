package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Opens an exact paid-revival attempt on the current Hytale world thread. */
public final class HytalePaidRevivalWorldGateway {
    private final ComponentType<
            EntityStore, TameworkInventoryOperationReceiptsComponent>
            receiptType;
    private final SnapshotCodecRegistry snapshotCodecs;
    private final HytaleCompanionProjectionSpawnExecutor projections;
    private final PaidRevivalWorldExecutor executor;
    private final WorldThreadGuard threadGuard;
    private final AttemptGatewayFactory attemptGateways;

    public HytalePaidRevivalWorldGateway(
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull HytaleCompanionProjectionSpawnExecutor projections
    ) {
        this(
                receiptType,
                snapshotCodecs,
                projections,
                new PaidRevivalWorldExecutor(),
                Store::assertThread,
                null
        );
    }

    HytalePaidRevivalWorldGateway(
            ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            SnapshotCodecRegistry snapshotCodecs,
            HytaleCompanionProjectionSpawnExecutor projections,
            PaidRevivalWorldExecutor executor,
            WorldThreadGuard threadGuard,
            AttemptGatewayFactory attemptGateways
    ) {
        this.receiptType = Objects.requireNonNull(
                receiptType, "receiptType"
        );
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs, "snapshotCodecs"
        );
        this.projections = Objects.requireNonNull(
                projections, "projections"
        );
        this.executor = Objects.requireNonNull(executor, "executor");
        this.threadGuard = Objects.requireNonNull(
                threadGuard, "threadGuard"
        );
        this.attemptGateways = attemptGateways == null
                ? this::newAttemptGateway
                : attemptGateways;
    }

    @Nonnull
    public CompletionStage<PaidRevivalLiveResult> applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull PaidRevivalRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        try {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(operation, "operation");
            return executeOnWorldThread(
                    executor,
                    request,
                    operation,
                    () -> threadGuard.assertCurrent(store),
                    () -> attemptGateways.create(
                            world, store, request, operation
                    )
            );
        } catch (RuntimeException | LinkageError failure) {
            return PaidRevivalLiveResult.unknown(
                    "paid_revival_world_thread_unavailable",
                    failure
            ).completed();
        }
    }

    private AttemptGateway newAttemptGateway(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
            OperationEnvelope operation
    ) {
        return new HytalePaidRevivalAttemptGateway(
                world,
                store,
                request,
                operation,
                receiptType,
                snapshotCodecs,
                projections
        );
    }

    static CompletionStage<PaidRevivalLiveResult> executeOnWorldThread(
            PaidRevivalWorldExecutor executor,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            Runnable threadAssertion,
            Supplier<AttemptGateway> attemptSupplier
    ) {
        if (executor == null || request == null || operation == null
                || threadAssertion == null || attemptSupplier == null) {
            return PaidRevivalLiveResult.unknown(
                    "paid_revival_world_request_invalid", null
            ).completed();
        }
        try {
            threadAssertion.run();
            AttemptGateway attempts = attemptSupplier.get();
            if (attempts == null) {
                return PaidRevivalLiveResult.unknown(
                        "paid_revival_world_attempt_missing", null
                ).completed();
            }
            CompletionStage<PaidRevivalLiveResult> result =
                    executor.execute(request, operation, attempts);
            return result == null
                    ? PaidRevivalLiveResult.unknown(
                    "paid_revival_world_result_missing", null
            ).completed()
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return PaidRevivalLiveResult.unknown(
                    "paid_revival_world_thread_unavailable",
                    failure
            ).completed();
        }
    }

    @FunctionalInterface
    interface WorldThreadGuard {
        void assertCurrent(Store<EntityStore> store);
    }

    @FunctionalInterface
    interface AttemptGatewayFactory {
        AttemptGateway create(
                World world,
                Store<EntityStore> store,
                PaidRevivalRequest request,
                OperationEnvelope operation
        );
    }
}
