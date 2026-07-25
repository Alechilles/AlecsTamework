package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactMutation;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactState;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.CompositeProbe;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ReceiptMutation;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ReceiptState;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.SaveResult;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.player.HytalePlayerDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.player.InventoryOperationReceipt;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Hytale player receipt and exact inventory-slot bridge for captured-item coop intake.
 *
 * <p>Every method is invoked on the actor world thread except asynchronous save completion.
 * The durability barrier resolves the actor again after each save before the protocol proceeds.</p>
 */
final class HytaleCompanionCoopCapturedItemAttemptGateway
        implements CompanionCoopCapturedItemAttempt {
    private final World world;
    private final Store<EntityStore> store;
    private final CompanionCoopCaptureRequest request;
    private final OperationEnvelope operation;
    private final CoopCapturedItemSourceEvidence source;
    private final InventoryOperationReceipt expectedReceipt;
    private final ComponentType<
            EntityStore,
            TameworkInventoryOperationReceiptsComponent> receiptType;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final HytalePlayerDurabilityBarrier durability;
    private final CoopTransitionEffectSink effects;
    private boolean effectPlayed;

    HytaleCompanionCoopCapturedItemAttemptGateway(
            World world,
            Store<EntityStore> store,
            CompanionCoopCaptureRequest request,
            OperationEnvelope operation,
            ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            HytaleCapturedArtifactAdapter artifacts,
            HytalePlayerDurabilityBarrier durability,
            CoopTransitionEffectSink effects
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.operation = operation;
        this.source = (CoopCapturedItemSourceEvidence) request.source();
        this.expectedReceipt = new InventoryOperationReceipt(
                source.retirementReceiptKey(),
                operation.operationId(),
                operation.kind(),
                Sha256Hash.ofUtf8(operation.payloadJson()),
                operation.createdAtMs()
        );
        this.receiptType = receiptType;
        this.artifacts = artifacts;
        this.durability = durability;
        this.effects = effects;
    }

    @Override
    public CompositeProbe probe(
            InventoryOperationReceipt expectedReceipt,
            CoopCapturedItemSourceEvidence expectedSource
    ) {
        if (!this.expectedReceipt.equals(expectedReceipt)
                || !source.equals(expectedSource)) {
            return new CompositeProbe(
                    ReceiptState.CONFLICT,
                    ArtifactState.CONFLICT,
                    null
            );
        }
        try {
            store.assertThread();
            ResolvedActor actor = resolveActor();
            if (actor.status() == ResolutionStatus.UNAVAILABLE) {
                return CompositeProbe.unavailable(actor.cause());
            }
            if (actor.status() == ResolutionStatus.CONFLICT) {
                return new CompositeProbe(
                        ReceiptState.CONFLICT,
                        ArtifactState.CONFLICT,
                        actor.cause()
                );
            }
            ReceiptState receiptState = receiptState(
                    actor.actor(), expectedReceipt
            );
            ArtifactState artifactState = actor.slot().probe(
                    expectedSource
            );
            return new CompositeProbe(
                    receiptState, artifactState, null
            );
        } catch (RuntimeException | LinkageError failure) {
            return CompositeProbe.unavailable(failure);
        }
    }

    @Override
    public ReceiptMutation installReceipt(
            InventoryOperationReceipt expectedReceipt
    ) {
        if (!this.expectedReceipt.equals(expectedReceipt)) {
            return ReceiptMutation.conflict(null);
        }
        ResolvedActor actor = resolveActorSafely();
        if (actor.status() == ResolutionStatus.UNAVAILABLE) {
            return ReceiptMutation.retryable(actor.cause());
        }
        if (actor.status() == ResolutionStatus.CONFLICT) {
            return ReceiptMutation.conflict(actor.cause());
        }
        CompositeProbe before = probe(expectedReceipt, source);
        if (before.receiptState() == ReceiptState.EXACT) {
            return before.artifactState() == ArtifactState.SOURCE
                    || before.artifactState() == ArtifactState.MARKED
                    ? ReceiptMutation.exact()
                    : ReceiptMutation.conflict(before.cause());
        }
        if (before.receiptState() != ReceiptState.ABSENT
                || before.artifactState() != ArtifactState.SOURCE) {
            return before.receiptState() == ReceiptState.UNAVAILABLE
                    || before.artifactState() == ArtifactState.UNAVAILABLE
                    ? ReceiptMutation.retryable(before.cause())
                    : ReceiptMutation.conflict(before.cause());
        }
        try {
            TameworkInventoryOperationReceiptsComponent current =
                    store.getComponent(actor.actor(), receiptType);
            TameworkInventoryOperationReceiptsComponent updated =
                    (current == null
                            ? new TameworkInventoryOperationReceiptsComponent()
                            : current).withReceipt(expectedReceipt);
            store.putComponent(actor.actor(), receiptType, updated);
            CompositeProbe after = probe(expectedReceipt, source);
            return after.receiptState() == ReceiptState.EXACT
                    && after.artifactState() == ArtifactState.SOURCE
                    ? ReceiptMutation.exact()
                    : ReceiptMutation.conflict(after.cause());
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptMutation.conflict(failure);
        }
    }

    @Override
    public ArtifactMutation markSource(
            CoopCapturedItemSourceEvidence expectedSource
    ) {
        if (!source.equals(expectedSource)) {
            return ArtifactMutation.conflict(null);
        }
        CompositeProbe before = probe(expectedReceipt, expectedSource);
        if (before.receiptState() != ReceiptState.EXACT) {
            return unavailable(before)
                    ? ArtifactMutation.retryable(before.cause())
                    : ArtifactMutation.conflict(before.cause());
        }
        if (before.artifactState() == ArtifactState.MARKED) {
            return ArtifactMutation.marked();
        }
        if (before.artifactState() != ArtifactState.SOURCE) {
            return unavailable(before)
                    ? ArtifactMutation.retryable(before.cause())
                    : ArtifactMutation.conflict(before.cause());
        }
        ResolvedActor actor = resolveActorSafely();
        if (actor.status() == ResolutionStatus.UNAVAILABLE) {
            return ArtifactMutation.retryable(actor.cause());
        }
        if (actor.status() == ResolutionStatus.CONFLICT) {
            return ArtifactMutation.conflict(actor.cause());
        }
        return actor.slot().mark(expectedSource).mutation();
    }

    @Override
    public ArtifactMutation retireMarkedArtifact(
            CoopCapturedItemSourceEvidence expectedSource
    ) {
        if (!source.equals(expectedSource)) {
            return ArtifactMutation.conflict(null);
        }
        CompositeProbe before = probe(expectedReceipt, expectedSource);
        if (before.receiptState() != ReceiptState.EXACT) {
            return unavailable(before)
                    ? ArtifactMutation.retryable(before.cause())
                    : ArtifactMutation.conflict(before.cause());
        }
        if (before.artifactState() == ArtifactState.ABSENT) {
            return ArtifactMutation.absent();
        }
        if (before.artifactState() != ArtifactState.MARKED) {
            return unavailable(before)
                    ? ArtifactMutation.retryable(before.cause())
                    : ArtifactMutation.conflict(before.cause());
        }
        ResolvedActor actor = resolveActorSafely();
        if (actor.status() == ResolutionStatus.UNAVAILABLE) {
            return ArtifactMutation.retryable(actor.cause());
        }
        if (actor.status() == ResolutionStatus.CONFLICT) {
            return ArtifactMutation.conflict(actor.cause());
        }
        HytaleCoopCapturedItemInventorySlot.SlotMutation retired =
                actor.slot().retireMarked(expectedSource);
        if (retired.changedThisCall()) {
            playEffectOnce();
        }
        return retired.mutation();
    }

    @Override
    public ReceiptMutation removeReceipt(
            InventoryOperationReceipt expectedReceipt
    ) {
        if (!this.expectedReceipt.equals(expectedReceipt)) {
            return ReceiptMutation.conflict(null);
        }
        ResolvedActor actor = resolveActorSafely();
        if (actor.status() == ResolutionStatus.UNAVAILABLE) {
            return ReceiptMutation.retryable(actor.cause());
        }
        if (actor.status() == ResolutionStatus.CONFLICT) {
            return ReceiptMutation.conflict(actor.cause());
        }
        CompositeProbe before = probe(expectedReceipt, source);
        if (before.artifactState() != ArtifactState.ABSENT) {
            return before.artifactState() == ArtifactState.UNAVAILABLE
                    ? ReceiptMutation.retryable(before.cause())
                    : ReceiptMutation.conflict(before.cause());
        }
        if (before.receiptState() == ReceiptState.ABSENT) {
            return ReceiptMutation.exact();
        }
        if (before.receiptState() != ReceiptState.EXACT) {
            return before.receiptState() == ReceiptState.UNAVAILABLE
                    ? ReceiptMutation.retryable(before.cause())
                    : ReceiptMutation.conflict(before.cause());
        }
        try {
            TameworkInventoryOperationReceiptsComponent current =
                    store.getComponent(actor.actor(), receiptType);
            if (current == null) {
                return ReceiptMutation.conflict(null);
            }
            store.putComponent(
                    actor.actor(),
                    receiptType,
                    current.withoutReceipt(expectedReceipt.receiptKey())
            );
            CompositeProbe after = probe(expectedReceipt, source);
            return after.receiptState() == ReceiptState.ABSENT
                    && after.artifactState() == ArtifactState.ABSENT
                    ? ReceiptMutation.exact()
                    : ReceiptMutation.conflict(after.cause());
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptMutation.conflict(failure);
        }
    }

    @Override
    public CompletionStage<SaveResult> persistActor() {
        CompletionStage<HytalePlayerDurabilityBarrier.SaveResult> save =
                durability.saveActor();
        if (save == null) {
            return CompletableFuture.completedFuture(
                    SaveResult.retryable(null)
            );
        }
        CompletableFuture<SaveResult> mapped = new CompletableFuture<>();
        save.whenComplete((result, failure) -> {
            if (failure != null || result == null || !result.saved()) {
                mapped.complete(SaveResult.retryable(
                        failure != null
                                ? failure
                                : result == null ? null : result.failure()
                ));
            } else {
                mapped.complete(SaveResult.saved());
            }
        });
        return mapped;
    }

    @Override
    public CompletionStage<LiveOperationResult> resumeOnActorWorldThread(
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        return durability.resumeOnWorldThread(
                continuation,
                () -> LiveOperationResult.retryable(
                        "coop_capture_item_world_instance_changed", null
                )
        );
    }

    private ReceiptState receiptState(
            Ref<EntityStore> actor,
            InventoryOperationReceipt expectedReceipt
    ) {
        if (receiptType == null) {
            return ReceiptState.UNAVAILABLE;
        }
        TameworkInventoryOperationReceiptsComponent component =
                store.getComponent(actor, receiptType);
        InventoryOperationReceipt actual = component == null
                ? null
                : component.receiptFor(expectedReceipt.receiptKey());
        if (actual == null) {
            return ReceiptState.ABSENT;
        }
        return expectedReceipt.equals(actual)
                ? ReceiptState.EXACT
                : ReceiptState.CONFLICT;
    }

    private boolean unavailable(CompositeProbe probe) {
        return probe.receiptState() == ReceiptState.UNAVAILABLE
                || probe.artifactState() == ArtifactState.UNAVAILABLE;
    }

    private ResolvedActor resolveActorSafely() {
        try {
            store.assertThread();
            return resolveActor();
        } catch (RuntimeException | LinkageError failure) {
            return ResolvedActor.unavailable(failure);
        }
    }

    private ResolvedActor resolveActor() {
        if (world == null || store == null || request == null
                || operation == null || source == null
                || artifacts == null || durability == null
                || receiptType == null) {
            return ResolvedActor.unavailable(null);
        }
        Ref<EntityStore> actor = world.getEntityRef(source.actorUuid());
        if (actor == null || !actor.isValid()) {
            return ResolvedActor.unavailable(null);
        }
        ItemContainer container = inventory(actor);
        if (container == null) {
            return ResolvedActor.unavailable(null);
        }
        int requestedSlot = source.inventoryPosition().slot();
        if (requestedSlot > Short.MAX_VALUE
                || requestedSlot >= container.getCapacity()) {
            return ResolvedActor.conflict(null);
        }
        return ResolvedActor.resolved(
                actor,
                new HytaleCoopCapturedItemInventorySlot(
                        container,
                        (short) requestedSlot,
                        artifacts
                )
        );
    }

    @Nullable
    private ItemContainer inventory(Ref<EntityStore> actor) {
        CoopCapturedItemInventoryPosition.Section section =
                source.inventoryPosition().section();
        InventoryComponent component = switch (section) {
            case HOTBAR -> store.getComponent(
                    actor, InventoryComponent.Hotbar.getComponentType()
            );
            case STORAGE -> store.getComponent(
                    actor, InventoryComponent.Storage.getComponentType()
            );
            case BACKPACK -> store.getComponent(
                    actor, InventoryComponent.Backpack.getComponentType()
            );
        };
        return component == null ? null : component.getInventory();
    }

    private void playEffectOnce() {
        if (effectPlayed || effects == null) {
            return;
        }
        effectPlayed = true;
        try {
            effects.play(
                    world,
                    request.targetSlot().x() + 0.5D,
                    request.targetSlot().y() + 0.5D,
                    request.targetSlot().z() + 0.5D,
                    request.targetSlot().coopId()
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Presentation cannot change an exact item retirement result.
        }
    }

    private enum ResolutionStatus {
        RESOLVED,
        UNAVAILABLE,
        CONFLICT
    }

    private record ResolvedActor(
            ResolutionStatus status,
            @Nullable Ref<EntityStore> actor,
            @Nullable HytaleCoopCapturedItemInventorySlot slot,
            @Nullable Throwable cause
    ) {
        private static ResolvedActor resolved(
                Ref<EntityStore> actor,
                HytaleCoopCapturedItemInventorySlot slot
        ) {
            return new ResolvedActor(
                    ResolutionStatus.RESOLVED, actor, slot, null
            );
        }

        private static ResolvedActor unavailable(
                @Nullable Throwable cause
        ) {
            return new ResolvedActor(
                    ResolutionStatus.UNAVAILABLE, null, null, cause
            );
        }

        private static ResolvedActor conflict(@Nullable Throwable cause) {
            return new ResolvedActor(
                    ResolutionStatus.CONFLICT, null, null, cause
            );
        }
    }
}
