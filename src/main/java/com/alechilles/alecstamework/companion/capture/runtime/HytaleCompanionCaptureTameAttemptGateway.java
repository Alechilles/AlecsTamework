package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AccessProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MarkerAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.TargetProbe;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.chunk
        .HytaleEntityChunkDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.player
        .HytalePlayerDurabilityBarrier;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Hytale source/access/durability adapter composed with a focused live-NPC target gateway.
 */
final class HytaleCompanionCaptureTameAttemptGateway
        implements AttemptGateway {
    private static final long ROLE_POLL_DELAY_MS = 50L;

    private final World world;
    private final Store<EntityStore> store;
    private final CompanionCaptureRequest request;
    private final HytaleCompanionCaptureAttemptGateway source;
    private final HytaleCaptureTameTargetGateway target;
    private final HytalePlayerDurabilityBarrier actorDurability;
    private final HytaleEntityChunkDurabilityBarrier targetDurability;

    HytaleCompanionCaptureTameAttemptGateway(
            World world,
            Store<EntityStore> store,
            CompanionCaptureRequest request,
            OperationEnvelope operation,
            HytaleCapturedArtifactAdapter artifacts,
            ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent> receiptType,
            ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent> retirementType
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.source = new HytaleCompanionCaptureAttemptGateway(
                world, store, request, artifacts, receiptType,
                retirementType, operation
        );
        this.target = new HytaleCaptureTameTargetGateway(
                world, store, request, operation
        );
        this.actorDurability = new HytalePlayerDurabilityBarrier(
                world,
                store,
                request.targetWorldKey(),
                request.source().actorUuid()
        );
        this.targetDurability =
                new HytaleEntityChunkDurabilityBarrier(
                        world, store, request.targetAlias().value()
                );
    }

    @Override
    public ResolvedCaptureSourceWorldExecutor.SpendProbe probe() {
        return source.probe();
    }

    @Override
    public ResolvedCaptureSourceWorldExecutor.ReceiptAttempt
    installReceipt() {
        return source.installReceipt();
    }

    @Override
    public ResolvedCaptureSourceWorldExecutor.ConsumptionAttempt
    consumeReceiptedSource() {
        return source.consumeReceiptedSource();
    }

    @Override
    public AccessProbe probeCommandAccess() {
        try {
            Player player = resolveActorPlayer();
            Inventory inventory =
                    player == null ? null : player.getInventory();
            CombinedItemContainer combined = inventory == null
                    ? null
                    : inventory
                    .getCombinedBackpackStorageHotbarFirst();
            if (combined == null) {
                return AccessProbe.conflict(null);
            }
            Set<String> accepted = new HashSet<>(
                    request.tameAndLinkEvidence().live()
                            .commandAccess().accessItemIds()
            );
            long quantity = 0;
            for (short slot = 0; slot < combined.getCapacity(); slot++) {
                ItemStack stack = combined.getItemStack(slot);
                if (stack != null && !stack.isEmpty()
                        && accepted.contains(stack.getItemId())) {
                    quantity = Math.addExact(
                            quantity, stack.getQuantity()
                    );
                }
            }
            if (accepted.contains(request.source().sourceItemId())) {
                quantity--;
            }
            return quantity > 0
                    ? AccessProbe.present()
                    : AccessProbe.missing();
        } catch (RuntimeException | LinkageError failure) {
            return AccessProbe.conflict(failure);
        }
    }

    @Override
    public TargetProbe probeTarget() {
        return target.probe();
    }

    @Override
    public boolean targetRoleResolvable() {
        return target.targetRoleResolvable();
    }

    @Override
    public MarkerAttempt installTargetMarker() {
        return target.installMarker();
    }

    @Override
    public MutationAttempt convergeTarget() {
        return target.converge();
    }

    @Override
    public CompletionStage<ReceiptPersistence> persistActor() {
        return actorDurability.saveActor().thenApply(saved ->
                saved.saved()
                        ? ReceiptPersistence.saved()
                        : ReceiptPersistence.retryable(saved.failure()));
    }

    @Override
    public CompletionStage<ReceiptPersistence> persistTarget() {
        return targetDurability.saveTarget().thenApply(saved ->
                saved.saved()
                        ? ReceiptPersistence.saved()
                        : ReceiptPersistence.retryable(saved.failure()));
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnWorldThread(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        return resume(continuation);
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeAfterWorldTick(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        CompletableFuture.runAsync(
                () -> enqueueResume(continuation, completion),
                CompletableFuture.delayedExecutor(
                        ROLE_POLL_DELAY_MS, TimeUnit.MILLISECONDS
                )
        ).exceptionally(failure -> {
            completion.completeExceptionally(failure);
            return null;
        });
        return completion;
    }

    private Player resolveActorPlayer() {
        Ref<EntityStore> actor =
                world.getEntityRef(request.source().actorUuid());
        ComponentType<EntityStore, Player> playerType =
                Player.getComponentType();
        return actor == null || !actor.isValid() || playerType == null
                ? null
                : store.getComponent(actor, playerType);
    }

    private void enqueueResume(
            Supplier<CompletionStage<LiveOperationResult>> continuation,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            world.execute(() -> resumeInto(continuation, completion));
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }

    private CompletionStage<LiveOperationResult> resume(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        enqueueResume(continuation, completion);
        return completion;
    }

    private void resumeInto(
            Supplier<CompletionStage<LiveOperationResult>> continuation,
            CompletableFuture<LiveOperationResult> completion
    ) {
        try {
            World current = Universe.get().getWorld(
                    request.targetWorldKey()
            );
            if (current != world
                    || world.getEntityStore().getStore() != store) {
                completion.complete(LiveOperationResult.retryable(
                        "capture_tame_link_world_instance_changed", null
                ));
                return;
            }
            store.assertThread();
            CompletionStage<LiveOperationResult> result =
                    continuation.get();
            if (result == null) {
                completion.completeExceptionally(
                        new IllegalStateException(
                                "Tame/link continuation returned no result"
                        )
                );
                return;
            }
            result.whenComplete((resolved, failure) -> {
                if (failure != null) {
                    completion.completeExceptionally(failure);
                } else {
                    completion.complete(resolved);
                }
            });
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }
}
