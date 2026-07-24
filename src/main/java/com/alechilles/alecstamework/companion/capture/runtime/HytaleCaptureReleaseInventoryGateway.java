package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CaptureReleaseSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReplacementAttempt;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Exact hotbar artifact resolution and source-to-receipt compare-and-set. */
final class HytaleCaptureReleaseInventoryGateway {
    private final World world;
    private final Store<EntityStore> store;
    private final CompanionCaptureReleaseRequest request;
    private final HytaleCapturedArtifactAdapter artifacts;

    HytaleCaptureReleaseInventoryGateway(
            World world,
            Store<EntityStore> store,
            CompanionCaptureReleaseRequest request,
            HytaleCapturedArtifactAdapter artifacts
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.artifacts = artifacts;
    }

    InventoryProbe probe() {
        final InventoryResolution inventory;
        try {
            inventory = resolve();
        } catch (RuntimeException | LinkageError unavailable) {
            return InventoryProbe.unavailable(unavailable);
        }
        return switch (inventory.status()) {
            case SOURCE -> InventoryProbe.source();
            case RECEIPT -> InventoryProbe.receipt();
            case UNAVAILABLE -> InventoryProbe.unavailable(inventory.cause());
            case CONFLICT -> InventoryProbe.conflict(inventory.cause());
        };
    }

    ReplacementAttempt replaceSourceWithReceipt() {
        InventoryResolution resolution;
        try {
            resolution = resolve();
        } catch (RuntimeException | LinkageError failure) {
            return ReplacementAttempt.unavailable(failure);
        }
        if (resolution.status() == InventoryStatus.UNAVAILABLE) {
            return ReplacementAttempt.unavailable(resolution.cause());
        }
        if (resolution.status() == InventoryStatus.RECEIPT) {
            return ReplacementAttempt.receipt();
        }
        if (resolution.status() != InventoryStatus.SOURCE) {
            return ReplacementAttempt.ambiguous(resolution.cause());
        }
        ResolvedInventory inventory = resolution.inventory();
        ItemStack current;
        try {
            current = inventory.container().getItemStack(inventory.slot());
            InventoryProbe before = classify(current);
            if (before.status() == InventoryStatus.RECEIPT) {
                return ReplacementAttempt.receipt();
            }
            if (before.status() != InventoryStatus.SOURCE) {
                return ReplacementAttempt.ambiguous(before.cause());
            }
        } catch (RuntimeException | LinkageError failure) {
            return ReplacementAttempt.ambiguous(failure);
        }
        try {
            ItemStack replacement = artifacts.toItemStack(
                    request.source().receiptArtifact()
            );
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
        if (after.status() == InventoryStatus.RECEIPT) {
            return ReplacementAttempt.receipt();
        }
        if (after.status() == InventoryStatus.SOURCE
                && !Boolean.TRUE.equals(replaceSucceeded)) {
            return ReplacementAttempt.sourceUnchanged(failure);
        }
        return ReplacementAttempt.ambiguous(
                failure != null ? failure : after.cause()
        );
    }

    private InventoryProbe classify(@Nullable ItemStack stack) {
        CaptureReleaseSourceEvidence source = request.source();
        if (artifacts.matches(stack, source.receiptArtifact())) {
            return InventoryProbe.receipt();
        }
        if (artifacts.matches(stack, source.sourceArtifact())) {
            return InventoryProbe.source();
        }
        return InventoryProbe.conflict(null);
    }

    private InventoryResolution resolve() {
        Ref<EntityStore> actor =
                world.getEntityRef(request.source().actorUuid());
        if (actor == null || !actor.isValid()) {
            return InventoryResolution.unavailable(null);
        }
        ComponentType<EntityStore, InventoryComponent.Hotbar> hotbarType =
                InventoryComponent.Hotbar.getComponentType();
        if (hotbarType == null) {
            return InventoryResolution.unavailable(null);
        }
        InventoryComponent.Hotbar hotbar =
                store.getComponent(actor, hotbarType);
        ItemContainer container = hotbar == null
                ? null
                : hotbar.getInventory();
        if (container == null) {
            return InventoryResolution.unavailable(null);
        }
        int capacity = container.getCapacity();
        List<InventoryStatus> statuses = new ArrayList<>(capacity);
        for (int slot = 0; slot < capacity; slot++) {
            statuses.add(classify(
                    container.getItemStack((short) slot)
            ).status());
        }
        SlotSelection selection = selectMatches(statuses);
        if (selection.status() == InventoryStatus.CONFLICT) {
            return InventoryResolution.conflict(null);
        }
        return InventoryResolution.resolved(
                selection.status(),
                new ResolvedInventory(container, (short) selection.slot())
        );
    }

    static SlotSelection selectMatches(List<InventoryStatus> statuses) {
        if (statuses == null) {
            return SlotSelection.conflict();
        }
        int exactSlot = -1;
        InventoryStatus exactStatus = null;
        for (int slot = 0; slot < statuses.size(); slot++) {
            InventoryStatus status = statuses.get(slot);
            if (status == null || status == InventoryStatus.UNAVAILABLE) {
                return SlotSelection.conflict();
            }
            if (status != InventoryStatus.SOURCE
                    && status != InventoryStatus.RECEIPT) {
                continue;
            }
            if (exactStatus != null) {
                return SlotSelection.conflict();
            }
            exactStatus = status;
            exactSlot = slot;
        }
        return exactStatus == null
                ? SlotSelection.conflict()
                : new SlotSelection(exactStatus, exactSlot);
    }

    private record ResolvedInventory(
            ItemContainer container,
            short slot
    ) {
    }

    record SlotSelection(InventoryStatus status, int slot) {
        private static SlotSelection conflict() {
            return new SlotSelection(InventoryStatus.CONFLICT, -1);
        }
    }

    private record InventoryResolution(
            InventoryStatus status,
            @Nullable ResolvedInventory inventory,
            @Nullable Throwable cause
    ) {
        private static InventoryResolution resolved(
                InventoryStatus status,
                ResolvedInventory inventory
        ) {
            return new InventoryResolution(status, inventory, null);
        }

        private static InventoryResolution unavailable(
                @Nullable Throwable cause
        ) {
            return new InventoryResolution(
                    InventoryStatus.UNAVAILABLE,
                    null,
                    cause
            );
        }

        private static InventoryResolution conflict(
                @Nullable Throwable cause
        ) {
            return new InventoryResolution(
                    InventoryStatus.CONFLICT,
                    null,
                    cause
            );
        }
    }
}
