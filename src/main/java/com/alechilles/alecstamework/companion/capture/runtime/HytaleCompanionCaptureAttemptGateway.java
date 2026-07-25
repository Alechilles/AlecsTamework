package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.InventoryProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.InventoryStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.ReplacementAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.RetirementAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureWorldExecutor.TargetProbe;
import com.alechilles.alecstamework.companion.capture.runtime.ResolvedCaptureSourceWorldExecutor.ConsumptionAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.ResolvedCaptureSourceWorldExecutor.ReceiptAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.ResolvedCaptureSourceWorldExecutor.SpendProbe;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/**
 * Thin Hytale ECS and inventory bridge for the pure companion-capture state machine.
 *
 * <p>Every inventory mutation is immediately classified by exact readback inside this class, so
 * an exception after a possible write cannot escape to the dispatcher as an unsafe generic
 * retry.</p>
 */
final class HytaleCompanionCaptureAttemptGateway implements AttemptGateway {
    private final World world;
    private final Store<EntityStore> store;
    private final CompanionCaptureRequest request;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final ComponentType<
            EntityStore,
            TameworkCaptureSourceReceiptsComponent> receiptType;
    private boolean consumedThisCall;

    HytaleCompanionCaptureAttemptGateway(
            World world,
            Store<EntityStore> store,
            CompanionCaptureRequest request,
            HytaleCapturedArtifactAdapter artifacts,
            ComponentType<
                    EntityStore,
                    TameworkCaptureSourceReceiptsComponent> receiptType
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.artifacts = artifacts;
        this.receiptType = receiptType;
    }

    @Override
    public SpendProbe probe() {
        ResolvedInventory inventory = resolveInventory();
        if (inventory == null || receiptType == null) {
            return SpendProbe.conflict(null);
        }
        CaptureSourceReceipt expected = CaptureSourceReceipt.exact(request);
        TameworkCaptureSourceReceiptsComponent component =
                store.getComponent(inventory.actor(), receiptType);
        CaptureSourceReceipt receipt = component == null
                ? null
                : component.receiptFor(request.source().slot());
        ItemStack current = inventory.container().getItemStack(
                inventory.slot()
        );
        if (receipt == null) {
            if (matchesSource(current)) {
                return SpendProbe.source();
            }
            return current == null || current.isEmpty()
                    ? SpendProbe.absent()
                    : SpendProbe.conflict(null);
        }
        if (!expected.equals(receipt)) {
            return SpendProbe.conflict(null);
        }
        if (matchesSource(current)) {
            return SpendProbe.receiptedSource();
        }
        return matchesRemainder(current)
                ? SpendProbe.spent()
                : SpendProbe.conflict(null);
    }

    @Override
    public ReceiptAttempt installReceipt() {
        ResolvedInventory inventory = resolveInventory();
        if (inventory == null || receiptType == null) {
            return ReceiptAttempt.ambiguous(null);
        }
        SpendProbe before = probe();
        if (before.status()
                == ResolvedCaptureSourceWorldExecutor.SpendStatus
                .RECEIPTED_SOURCE
                || before.status()
                == ResolvedCaptureSourceWorldExecutor.SpendStatus.SPENT) {
            return ReceiptAttempt.receipted();
        }
        if (before.status()
                != ResolvedCaptureSourceWorldExecutor.SpendStatus.SOURCE) {
            return ReceiptAttempt.ambiguous(before.cause());
        }
        try {
            TameworkCaptureSourceReceiptsComponent current =
                    store.getComponent(inventory.actor(), receiptType);
            TameworkCaptureSourceReceiptsComponent updated =
                    (current == null
                            ? new TameworkCaptureSourceReceiptsComponent()
                            : current).withReceipt(
                            CaptureSourceReceipt.exact(request)
                    );
            store.putComponent(
                    inventory.actor(), receiptType, updated
            );
            SpendProbe after = probe();
            return after.status()
                    == ResolvedCaptureSourceWorldExecutor.SpendStatus
                    .RECEIPTED_SOURCE
                    ? ReceiptAttempt.receipted()
                    : ReceiptAttempt.ambiguous(after.cause());
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptAttempt.ambiguous(failure);
        }
    }

    @Override
    public ConsumptionAttempt consumeReceiptedSource() {
        ResolvedInventory inventory = resolveInventory();
        if (inventory == null) {
            return ConsumptionAttempt.ambiguous(null);
        }
        SpendProbe before = probe();
        if (before.status()
                == ResolvedCaptureSourceWorldExecutor.SpendStatus.SPENT) {
            return ConsumptionAttempt.spent();
        }
        if (before.status()
                != ResolvedCaptureSourceWorldExecutor.SpendStatus
                .RECEIPTED_SOURCE) {
            return ConsumptionAttempt.ambiguous(before.cause());
        }
        ItemStack current = inventory.container().getItemStack(
                inventory.slot()
        );
        ItemStack remaining = request.source().remainingQuantity() == 0
                ? ItemStack.EMPTY
                : current.withQuantity(
                        request.source().remainingQuantity()
                );
        try {
            boolean succeeded = inventory.container()
                    .replaceItemStackInSlot(
                            inventory.slot(), current, remaining
                    ).succeeded();
            SpendProbe after = probe();
            if (after.status()
                    == ResolvedCaptureSourceWorldExecutor.SpendStatus.SPENT) {
                consumedThisCall = true;
                return ConsumptionAttempt.spent();
            }
            return after.status()
                    == ResolvedCaptureSourceWorldExecutor.SpendStatus
                    .RECEIPTED_SOURCE
                    && !succeeded
                    ? ConsumptionAttempt.stillPresent(null)
                    : ConsumptionAttempt.ambiguous(after.cause());
        } catch (RuntimeException | LinkageError failure) {
            SpendProbe after = probe();
            if (after.status()
                    == ResolvedCaptureSourceWorldExecutor.SpendStatus.SPENT) {
                consumedThisCall = true;
                return ConsumptionAttempt.spent();
            }
            return ConsumptionAttempt.ambiguous(failure);
        }
    }

    boolean consumedThisCall() {
        return consumedThisCall;
    }

    @Override
    public InventoryProbe probeInventory() {
        ResolvedInventory inventory = resolveInventory();
        if (inventory == null) {
            return InventoryProbe.conflict(null);
        }
        return classify(inventory.container().getItemStack(inventory.slot()));
    }

    @Override
    public TargetProbe probeTarget() {
        Ref<EntityStore> target =
                world.getEntityRef(request.targetAlias().value());
        if (target == null || !target.isValid()) {
            return TargetProbe.absent();
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        if (uuidType == null || npcType == null) {
            return TargetProbe.conflict(null);
        }
        UUIDComponent identity = store.getComponent(target, uuidType);
        NPCEntity npc = store.getComponent(target, npcType);
        return identity != null
                && request.targetAlias().value().equals(identity.getUuid())
                && npc != null
                && request.targetAlias().value().equals(npc.getUuid())
                ? TargetProbe.exact()
                : TargetProbe.conflict(null);
    }

    @Override
    public ReplacementAttempt replaceSourceWithArtifact() {
        ResolvedInventory inventory;
        try {
            inventory = resolveInventory();
        } catch (RuntimeException | LinkageError failure) {
            return ReplacementAttempt.ambiguous(failure);
        }
        if (inventory == null) {
            return ReplacementAttempt.ambiguous(null);
        }

        ItemStack current;
        try {
            current = inventory.container().getItemStack(inventory.slot());
            InventoryProbe before = classify(current);
            if (before.status() == InventoryStatus.ARTIFACT) {
                return ReplacementAttempt.artifact();
            }
            if (before.status() != InventoryStatus.SOURCE) {
                return ReplacementAttempt.ambiguous(before.cause());
            }
        } catch (RuntimeException | LinkageError failure) {
            return ReplacementAttempt.ambiguous(failure);
        }

        try {
            ItemStack replacement = artifacts.toItemStack(request.artifact());
            boolean succeeded = inventory.container().replaceItemStackInSlot(
                    inventory.slot(),
                    current,
                    replacement
            ).succeeded();
            return classifyReadback(inventory, succeeded, null);
        } catch (RuntimeException | LinkageError failure) {
            return classifyReadback(inventory, null, failure);
        }
    }

    @Override
    public RetirementAttempt retireExactTarget() {
        TargetProbe before;
        try {
            before = probeTarget();
        } catch (RuntimeException | LinkageError failure) {
            return RetirementAttempt.conflict(failure);
        }
        if (before.status()
                == CompanionCaptureWorldExecutor.TargetStatus.ABSENT) {
            return RetirementAttempt.absent();
        }
        if (before.status()
                == CompanionCaptureWorldExecutor.TargetStatus.CONFLICT) {
            return RetirementAttempt.conflict(before.cause());
        }

        Ref<EntityStore> target =
                world.getEntityRef(request.targetAlias().value());
        if (target == null || !target.isValid()) {
            return RetirementAttempt.absent();
        }
        try {
            store.removeEntity(target, RemoveReason.REMOVE);
        } catch (RuntimeException | LinkageError failure) {
            return classifyRemovalReadback(failure);
        }
        return classifyRemovalReadback(null);
    }

    private ReplacementAttempt classifyReadback(
            ResolvedInventory inventory,
            @Nullable Boolean replaceSucceeded,
            @Nullable Throwable failure
    ) {
        final InventoryProbe after;
        try {
            after = classify(
                    inventory.container().getItemStack(inventory.slot())
            );
        } catch (RuntimeException | LinkageError readbackFailure) {
            if (failure != null) {
                readbackFailure.addSuppressed(failure);
            }
            return ReplacementAttempt.ambiguous(readbackFailure);
        }
        if (after.status() == InventoryStatus.ARTIFACT) {
            return ReplacementAttempt.artifact();
        }
        if (after.status() == InventoryStatus.SOURCE
                && !Boolean.TRUE.equals(replaceSucceeded)) {
            return ReplacementAttempt.sourceUnchanged(failure);
        }
        return ReplacementAttempt.ambiguous(failure != null
                ? failure
                : after.cause());
    }

    private RetirementAttempt classifyRemovalReadback(
            @Nullable Throwable failure
    ) {
        final TargetProbe after;
        try {
            after = probeTarget();
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

    private InventoryProbe classify(@Nullable ItemStack stack) {
        if (artifacts.matches(stack, request.artifact())) {
            return InventoryProbe.artifact();
        }
        if (stack == null || stack.isEmpty()) {
            return InventoryProbe.conflict(null);
        }
        try {
            CapturedArtifact live = artifacts.toArtifact(stack);
            CaptureSourceEvidence source = request.source();
            return source.sourceItemId().equals(live.itemId())
                    && source.quantity() == live.quantity()
                    && source.beforeFingerprint().equals(live.artifactHash())
                    ? InventoryProbe.source()
                    : InventoryProbe.conflict(null);
        } catch (RuntimeException | LinkageError failure) {
            return InventoryProbe.conflict(failure);
        }
    }

    @Nullable
    private ResolvedInventory resolveInventory() {
        Ref<EntityStore> actor = resolveActor();
        if (actor == null || !actor.isValid()) {
            return null;
        }
        ComponentType<EntityStore, InventoryComponent.Hotbar> hotbarType =
                InventoryComponent.Hotbar.getComponentType();
        if (hotbarType == null) {
            return null;
        }
        InventoryComponent.Hotbar hotbar =
                store.getComponent(actor, hotbarType);
        ItemContainer container = hotbar == null ? null : hotbar.getInventory();
        int slot = request.source().slot();
        if (container == null || slot < 0 || slot >= container.getCapacity()) {
            return null;
        }
        return new ResolvedInventory(actor, container, (short) slot);
    }

    @Nullable
    private Ref<EntityStore> resolveActor() {
        Ref<EntityStore> actor =
                world.getEntityRef(request.source().actorUuid());
        return actor == null || !actor.isValid() ? null : actor;
    }

    private boolean matchesSource(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            CapturedArtifact live = artifacts.toArtifact(stack);
            CaptureSourceEvidence source = request.source();
            return source.sourceItemId().equals(live.itemId())
                    && source.quantity() == live.quantity()
                    && source.beforeFingerprint().equals(
                    live.artifactHash()
            );
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean matchesRemainder(@Nullable ItemStack stack) {
        CaptureSourceEvidence source = request.source();
        if (source.remainingQuantity() == 0) {
            return stack == null || stack.isEmpty();
        }
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            CapturedArtifact live = artifacts.toArtifact(stack);
            return source.sourceItemId().equals(live.itemId())
                    && source.remainingQuantity() == live.quantity()
                    && source.remainingFingerprint().equals(
                    live.artifactHash()
            );
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private record ResolvedInventory(
            Ref<EntityStore> actor,
            ItemContainer container,
            short slot
    ) {
    }
}
