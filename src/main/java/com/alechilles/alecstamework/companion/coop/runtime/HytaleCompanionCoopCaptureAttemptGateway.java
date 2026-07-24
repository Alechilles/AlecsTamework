package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCaptureReceipt;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.AttemptGateway;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.ReceiptPersistence;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.ReceiptProbe;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.RetirementAttempt;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCaptureWorldExecutor.SourceProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Thin Hytale bridge for physical coop receipts and exact source retirement.
 *
 * <p>The chunk saver is invoked on the world thread. Its completion callback performs no ECS
 * access and dispatches the executor continuation back through {@link World#execute(Runnable)}.</p>
 */
final class HytaleCompanionCoopCaptureAttemptGateway implements AttemptGateway {
    private final World world;
    private final Store<EntityStore> entityStore;
    private final CompanionCoopCaptureRequest request;
    private final OperationEnvelope operation;
    private final CoopCaptureReceipt expectedReceipt;
    private final ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent>
            receiptType;
    private final ComponentType<EntityStore, TameworkPersistenceRetirementComponent>
            retirementType;
    private final CoopTransitionEffectSink effects;

    HytaleCompanionCoopCaptureAttemptGateway(
            World world,
            Store<EntityStore> entityStore,
            CompanionCoopCaptureRequest request,
            OperationEnvelope operation,
            ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent> receiptType,
            ComponentType<EntityStore, TameworkPersistenceRetirementComponent>
                    retirementType,
            CoopTransitionEffectSink effects
    ) {
        this.world = world;
        this.entityStore = entityStore;
        this.request = request;
        this.operation = operation;
        this.expectedReceipt = CoopCaptureReceipt.exact(
                request, operation.operationId()
        );
        this.receiptType = receiptType;
        this.retirementType = retirementType;
        this.effects = effects;
    }

    @Override
    public ReceiptProbe probeReceipt() {
        ReceiptContext context = resolveReceiptContext();
        if (context == null) {
            return ReceiptProbe.unavailable(null);
        }
        try {
            TameworkCoopCaptureReceiptsComponent component =
                    context.store().getComponent(
                            context.blockRef(), receiptType
                    );
            if (component == null) {
                return ReceiptProbe.absent();
            }
            CoopCaptureReceipt receipt = component.receiptFor(
                    request.targetSlot()
            );
            if (receipt == null) {
                return ReceiptProbe.absent();
            }
            return expectedReceipt.equals(receipt)
                    ? ReceiptProbe.exact()
                    : ReceiptProbe.conflict(null);
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptProbe.conflict(failure);
        }
    }

    @Override
    public SourceProbe probeSource() {
        Ref<EntityStore> source =
                world.getEntityRef(request.source().sourceAlias().value());
        if (source == null || !source.isValid()) {
            return SourceProbe.absent();
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        if (uuidType == null || npcType == null) {
            return SourceProbe.conflict(null);
        }
        UUIDComponent identity = entityStore.getComponent(source, uuidType);
        NPCEntity npc = entityStore.getComponent(source, npcType);
        return identity != null
                && request.source().sourceAlias().value().equals(
                identity.getUuid()
        )
                && npc != null
                && request.source().sourceAlias().value().equals(npc.getUuid())
                ? SourceProbe.exact()
                : SourceProbe.conflict(null);
    }

    @Override
    public CompletionStage<ReceiptPersistence> persistExactReceipt() {
        ReceiptContext context = resolveReceiptContext();
        if (context == null) {
            return completed(ReceiptPersistence.retryable(null));
        }
        TameworkCoopCaptureReceiptsComponent updated;
        try {
            TameworkCoopCaptureReceiptsComponent current =
                    context.store().getComponent(
                            context.blockRef(), receiptType
                    );
            CoopCaptureReceipt existing = current == null
                    ? null
                    : current.receiptFor(request.targetSlot());
            if (!expectedReceipt.equals(existing)
                    && probeSource().status()
                    != CompanionCoopCaptureWorldExecutor.SourceStatus.EXACT) {
                return completed(ReceiptPersistence.conflict(null));
            }

            TameworkCoopCaptureReceiptsComponent base = current == null
                    ? new TameworkCoopCaptureReceiptsComponent()
                    : current;
            updated = base.withReceipt(expectedReceipt);
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.conflict(failure));
        }
        try {
            context.store().putComponent(
                    context.blockRef(), receiptType, updated
            );
            if (!exactReceiptInstalled(context)) {
                return completed(ReceiptPersistence.retryable(null));
            }

            context.blockState().markNeedsSaving(context.store());
            IChunkSaver saver = context.chunkStore().getSaver();
            if (saver == null) {
                return completed(ReceiptPersistence.retryable(null));
            }
            CompletableFuture<Void> save = saver.saveHolder(
                    context.chunk().getX(),
                    context.chunk().getZ(),
                    context.chunk().toHolder()
            );
            if (save == null) {
                return completed(ReceiptPersistence.retryable(null));
            }
            CompletableFuture<ReceiptPersistence> completion =
                    new CompletableFuture<>();
            save.whenComplete((ignored, failure) -> completion.complete(
                    failure == null
                            ? ReceiptPersistence.saved()
                            : ReceiptPersistence.retryable(failure)
            ));
            return completion;
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.retryable(failure));
        }
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnWorldThread(
            Supplier<LiveOperationResult> continuation
    ) {
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    World current = Universe.get().getWorld(
                            request.source().sourceWorldKey()
                    );
                    if (current != world
                            || world.getEntityStore().getStore()
                            != entityStore) {
                        completion.complete(LiveOperationResult.retryable(
                                "coop_capture_world_instance_changed", null
                        ));
                        return;
                    }
                    entityStore.assertThread();
                    completion.complete(continuation.get());
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    @Override
    public RetirementAttempt retireExactSource() {
        SourceProbe sourceProbe = probeSource();
        if (sourceProbe.status()
                == CompanionCoopCaptureWorldExecutor.SourceStatus.ABSENT) {
            return RetirementAttempt.absent();
        }
        if (sourceProbe.status()
                == CompanionCoopCaptureWorldExecutor.SourceStatus.CONFLICT) {
            return RetirementAttempt.conflict(sourceProbe.cause());
        }

        Ref<EntityStore> source =
                world.getEntityRef(request.source().sourceAlias().value());
        if (source == null || !source.isValid()) {
            return RetirementAttempt.absent();
        }
        TameworkPersistenceRetirementComponent expected =
                TameworkPersistenceRetirementComponent.exact(
                        request.profileId(), operation
                );
        Vector3d effectPosition = transitionPosition(source);
        Throwable removalFailure = null;
        try {
            TameworkPersistenceRetirementComponent existing =
                    entityStore.getComponent(source, retirementType);
            if (existing != null
                    && !existing.matches(request.profileId(), operation)) {
                return RetirementAttempt.conflict(null);
            }
            entityStore.putComponent(source, retirementType, expected);
            TameworkPersistenceRetirementComponent installed =
                    entityStore.getComponent(source, retirementType);
            if (installed == null
                    || !installed.matches(request.profileId(), operation)) {
                return RetirementAttempt.retryable(null);
            }
            entityStore.removeEntity(source, RemoveReason.REMOVE);
        } catch (RuntimeException | LinkageError failure) {
            removalFailure = failure;
        }
        RetirementAttempt result = classifyRetirementReadback(removalFailure);
        if (result.status()
                == CompanionCoopCaptureWorldExecutor.RetirementStatus.ABSENT) {
            playTransitionEffect(effectPosition);
        }
        return result;
    }

    @Nullable
    private Vector3d transitionPosition(Ref<EntityStore> source) {
        TransformComponent transform = entityStore.getComponent(
                source, TransformComponent.getComponentType()
        );
        Vector3d position = transform == null ? null : transform.getPosition();
        return position == null
                || !Double.isFinite(position.x)
                || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)
                ? null
                : new Vector3d(position);
    }

    private void playTransitionEffect(@Nullable Vector3d position) {
        if (position == null || effects == null) {
            return;
        }
        try {
            effects.play(
                    world,
                    position.x,
                    position.y,
                    position.z,
                    request.targetSlot().coopId()
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Presentation cannot change an exact retirement result.
        }
    }

    private RetirementAttempt classifyRetirementReadback(
            @Nullable Throwable failure
    ) {
        SourceProbe after;
        try {
            after = probeSource();
        } catch (RuntimeException | LinkageError readbackFailure) {
            if (failure != null) {
                readbackFailure.addSuppressed(failure);
            }
            return RetirementAttempt.retryable(readbackFailure);
        }
        return switch (after.status()) {
            case ABSENT -> RetirementAttempt.absent();
            case EXACT -> failure == null
                    ? RetirementAttempt.stillPresent()
                    : RetirementAttempt.retryable(failure);
            case CONFLICT -> RetirementAttempt.conflict(
                    failure != null ? failure : after.cause()
            );
        };
    }

    private boolean exactReceiptInstalled(ReceiptContext context) {
        TameworkCoopCaptureReceiptsComponent installed =
                context.store().getComponent(
                        context.blockRef(), receiptType
                );
        return installed != null && expectedReceipt.equals(
                installed.receiptFor(request.targetSlot())
        );
    }

    @Nullable
    private ReceiptContext resolveReceiptContext() {
        if (world == null || entityStore == null || receiptType == null
                || retirementType == null || world.getChunkStore() == null) {
            return null;
        }
        entityStore.assertThread();
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> store = chunkStore.getStore();
        if (store == null) {
            return null;
        }
        store.assertThread();
        WorldChunk chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(
                        request.targetSlot().x(),
                        request.targetSlot().z()
                )
        );
        if (chunk == null || chunk.getWorld() != world) {
            return null;
        }
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(
                request.targetSlot().x(),
                request.targetSlot().y(),
                request.targetSlot().z()
        );
        ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateType =
                BlockModule.BlockStateInfo.getComponentType();
        ComponentType<ChunkStore, CoopBlock> coopType =
                CoopBlock.getComponentType();
        if (blockRef == null || !blockRef.isValid()
                || blockStateType == null || coopType == null
                || store.getComponent(blockRef, coopType) == null) {
            return null;
        }
        BlockModule.BlockStateInfo blockState =
                store.getComponent(blockRef, blockStateType);
        if (blockState == null) {
            return null;
        }
        return new ReceiptContext(
                chunkStore, store, chunk, blockRef, blockState
        );
    }

    private CompletionStage<ReceiptPersistence> completed(
            ReceiptPersistence persistence
    ) {
        return CompletableFuture.completedFuture(persistence);
    }

    private record ReceiptContext(
            ChunkStore chunkStore,
            Store<ChunkStore> store,
            WorldChunk chunk,
            Ref<ChunkStore> blockRef,
            BlockModule.BlockStateInfo blockState
    ) {
    }
}
