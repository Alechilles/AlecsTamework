package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Production gateway for durable physical coop receipts and exact source retirement. */
public final class HytaleCompanionCoopCaptureWorldGateway
        implements CompanionCoopCaptureWorldGateway {
    private final ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent>
            receiptType;
    private final ComponentType<EntityStore, TameworkPersistenceRetirementComponent>
            retirementType;
    private final CompanionCoopCaptureWorldExecutor executor;
    private final CoopTransitionEffectSink effects;

    public HytaleCompanionCoopCaptureWorldGateway(
            @Nonnull ComponentType<
                    ChunkStore,
                    TameworkCoopCaptureReceiptsComponent
                    > receiptType,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirementType
    ) {
        this(
                receiptType,
                retirementType,
                CoopTransitionEffectSink.NONE,
                new CompanionCoopCaptureWorldExecutor()
        );
    }

    public HytaleCompanionCoopCaptureWorldGateway(
            @Nonnull ComponentType<
                    ChunkStore,
                    TameworkCoopCaptureReceiptsComponent
                    > receiptType,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirementType,
            @Nonnull CoopTransitionEffectSink effects
    ) {
        this(
                receiptType,
                retirementType,
                effects,
                new CompanionCoopCaptureWorldExecutor()
        );
    }

    HytaleCompanionCoopCaptureWorldGateway(
            ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent>
                    receiptType,
            ComponentType<EntityStore, TameworkPersistenceRetirementComponent>
                    retirementType,
            CoopTransitionEffectSink effects,
            CompanionCoopCaptureWorldExecutor executor
    ) {
        this.receiptType = Objects.requireNonNull(
                receiptType, "receiptType"
        );
        this.retirementType = Objects.requireNonNull(
                retirementType, "retirementType"
        );
        this.effects = Objects.requireNonNull(effects, "effects");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (world == null || store == null || request == null
                || operation == null) {
            return LiveOperationResult.unknown(
                    "coop_capture_world_context_missing", null
            ).completed();
        }
        try {
            store.assertThread();
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.unknown(
                    "coop_capture_world_thread_unavailable", failure
            ).completed();
        }
        return executor.execute(
                request,
                operation,
                new HytaleCompanionCoopCaptureAttemptGateway(
                        world,
                        store,
                        request,
                        operation,
                        receiptType,
                        retirementType,
                        effects
                )
        );
    }
}
