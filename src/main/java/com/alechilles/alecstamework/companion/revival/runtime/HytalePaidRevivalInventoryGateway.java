package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.revival.RevivalInventoryReservation;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ChargeAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ReceiptInstall;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ChargeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptProbe;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Exact Hytale inventory and generic player-receipt bridge for paid revival.
 */
final class HytalePaidRevivalInventoryGateway {
    private static final Map<String, Integer> SECTION_IDS = Map.of(
            "backpack", InventoryComponent.BACKPACK_SECTION_ID,
            "storage", InventoryComponent.STORAGE_SECTION_ID,
            "hotbar", InventoryComponent.HOTBAR_SECTION_ID
    );

    private final World world;
    private final Store<EntityStore> store;
    private final PaidRevivalRequest request;
    private final ComponentType<
            EntityStore, TameworkInventoryOperationReceiptsComponent>
            receiptType;
    private final HytalePaidRevivalReceiptPlan receipts;

    HytalePaidRevivalInventoryGateway(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.receiptType = receiptType;
        this.receipts = new HytalePaidRevivalReceiptPlan(
                request, operation
        );
    }

    ReceiptProbe probeReceipt() {
        try {
            InventoryView view = resolve();
            return view == null
                    ? ReceiptProbe.unavailable(null)
                    : receipts.probe(view.receiptComponent());
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptProbe.conflict(failure);
        }
    }

    ChargeProbe probeCharge() {
        if (request.exactCost().isEmpty()) {
            return ChargeProbe.empty();
        }
        try {
            InventoryView view = resolve();
            if (view == null) {
                return ChargeProbe.unavailable(null);
            }
            if (receipts.charged(view.receiptComponent())) {
                return ChargeProbe.charged();
            }
            SourcePlan plan = sourcePlan(view);
            if (plan.status() == SourceStatus.EXACT) {
                return ChargeProbe.unchanged();
            }
            return plan.status() == SourceStatus.UNAVAILABLE
                    ? ChargeProbe.unavailable(plan.cause())
                    : plan.status() == SourceStatus.CONFLICT
                    ? ChargeProbe.conflict(plan.cause())
                    : ChargeProbe.partial(plan.cause());
        } catch (RuntimeException | LinkageError failure) {
            return ChargeProbe.conflict(failure);
        }
    }

    ReceiptInstall installPendingReceipt() {
        try {
            InventoryView view = resolve();
            if (view == null) {
                return ReceiptInstall.unchanged(null);
            }
            if (request.exactCost().isEmpty()) {
                return releaseEmptyRecipeReceipts(view);
            }
            ReceiptProbe existing =
                    receipts.probe(view.receiptComponent());
            if (existing.status()
                    == PaidRevivalWorldEvidence.ReceiptStatus.EXACT) {
                return ReceiptInstall.exact();
            }
            if (existing.status()
                    == PaidRevivalWorldEvidence.ReceiptStatus.CONFLICT) {
                return ReceiptInstall.conflict(existing.cause());
            }
            TameworkInventoryOperationReceiptsComponent updated =
                    receipts.installPending(view.receiptComponent());
            store.putComponent(view.actor(), receiptType, updated);
            return receipts.pending(updated)
                    ? ReceiptInstall.exact()
                    : ReceiptInstall.conflict(null);
        } catch (IllegalStateException failure) {
            return ReceiptInstall.unchanged(failure);
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptInstall.conflict(failure);
        }
    }

    private ReceiptInstall releaseEmptyRecipeReceipts(
            InventoryView view
    ) {
        HytalePaidRevivalReceiptPlan.ReleasePlan release =
                receipts.releaseNoCharge(view.receiptComponent());
        if (release.status()
                == HytalePaidRevivalReceiptPlan.ReleaseStatus.CONFLICT) {
            return ReceiptInstall.conflict(null);
        }
        if (release.status()
                == HytalePaidRevivalReceiptPlan.ReleaseStatus.MUTATED) {
            store.putComponent(
                    view.actor(), receiptType, release.receipts()
            );
        }
        HytalePaidRevivalReceiptPlan.ReleasePlan proof =
                receipts.releaseNoCharge(
                        store.getComponent(view.actor(), receiptType)
                );
        return proof.status()
                == HytalePaidRevivalReceiptPlan.ReleaseStatus.ABSENT
                ? ReceiptInstall.exact()
                : ReceiptInstall.conflict(null);
    }

    ChargeAttempt consumeExactRecipe() {
        if (request.exactCost().isEmpty()) {
            return ChargeAttempt.charged();
        }
        try {
            InventoryView view = resolve();
            if (view == null) {
                return ChargeAttempt.retryable(null);
            }
            if (receipts.charged(view.receiptComponent())) {
                return ChargeAttempt.charged();
            }
            if (!receipts.pending(view.receiptComponent())) {
                return ChargeAttempt.conflict(null);
            }
            SourcePlan plan = sourcePlan(view);
            if (plan.status() != SourceStatus.EXACT) {
                return switch (plan.status()) {
                    case UNAVAILABLE ->
                            ChargeAttempt.retryable(plan.cause());
                    case PARTIAL ->
                            ChargeAttempt.partial(plan.cause());
                    case CONFLICT ->
                            ChargeAttempt.conflict(plan.cause());
                    case EXACT -> throw new IllegalStateException(
                            "Exact source plan was rejected"
                    );
                };
            }
            return applyCharge(view, plan);
        } catch (RuntimeException | LinkageError failure) {
            return ChargeAttempt.partial(failure);
        }
    }

    private ChargeAttempt applyCharge(
            InventoryView view,
            SourcePlan plan
    ) {
        AtomicBoolean mismatch = new AtomicBoolean();
        ListTransaction<ItemStackSlotTransaction> transaction =
                view.combined().replaceAll((slot, current) -> {
                    SlotMutation mutation = plan.mutations().get(slot);
                    if (mutation == null) {
                        return current;
                    }
                    if (!mutation.original().equals(current)) {
                        mismatch.set(true);
                        return current;
                    }
                    return current.withQuantity(
                            current.getQuantity() - mutation.quantity()
                    );
                });
        if (transaction == null || !transaction.succeeded()
                || mismatch.get() || !postStateMatches(view, plan)) {
            return ChargeAttempt.partial(null);
        }
        TameworkInventoryOperationReceiptsComponent charged =
                receipts.markCharged(view.receiptComponent());
        store.putComponent(view.actor(), receiptType, charged);
        return receipts.charged(charged)
                ? ChargeAttempt.charged()
                : ChargeAttempt.partial(null);
    }

    private boolean postStateMatches(
            InventoryView view,
            SourcePlan plan
    ) {
        for (Map.Entry<Short, SlotMutation> entry
                : plan.mutations().entrySet()) {
            ItemStack expected = entry.getValue().replacement();
            ItemStack current =
                    view.combined().getItemStack(entry.getKey());
            if (!java.util.Objects.equals(expected, current)) {
                return false;
            }
        }
        return true;
    }

    private SourcePlan sourcePlan(InventoryView view) {
        HashMap<Short, SlotMutation> mutations = new HashMap<>();
        for (RevivalInventoryReservation reservation
                : request.reservations()) {
            Section section =
                    view.sections().get(reservation.compartmentId());
            if (section == null
                    || reservation.slotIndex()
                    >= section.container().getCapacity()) {
                return SourcePlan.conflict(null);
            }
            int combinedIndex = Math.addExact(
                    section.offset(), reservation.slotIndex()
            );
            if (combinedIndex > Short.MAX_VALUE
                    || combinedIndex < 0) {
                return SourcePlan.conflict(null);
            }
            short combinedSlot = (short) combinedIndex;
            if (combinedSlot < 0
                    || mutations.containsKey(combinedSlot)) {
                return SourcePlan.conflict(null);
            }
            ItemStack current = section.container().getItemStack(
                    (short) reservation.slotIndex()
            );
            RevivalCostItem cost =
                    request.exactCost().get(reservation.costOrdinal());
            if (ItemStack.isEmpty(current)
                    || !cost.itemId().equals(current.getItemId())
                    || current.getQuantity() < reservation.quantity()
                    || !reservation.sourceStackFingerprint().equals(
                    HytalePaidRevivalStackFingerprint.of(current)
            )) {
                return SourcePlan.partial(null);
            }
            mutations.put(combinedSlot, new SlotMutation(
                    current,
                    current.withQuantity(
                            current.getQuantity() - reservation.quantity()
                    ),
                    reservation.quantity()
            ));
        }
        return SourcePlan.exact(Map.copyOf(mutations));
    }

    @Nullable
    private InventoryView resolve() {
        store.assertThread();
        Ref<EntityStore> actor = world.getEntityRef(
                request.familyKey().ownerId().value()
        );
        if (actor == null || !actor.isValid()
                || actor.getStore() != store
                || receiptType == null
                || Player.getComponentType() == null
                || store.getComponent(
                actor, Player.getComponentType()
        ) == null) {
            return null;
        }
        HashMap<String, Section> sections = new HashMap<>();
        int offset = 0;
        for (String id : java.util.List.of(
                "backpack", "storage", "hotbar"
        )) {
            ComponentType<EntityStore, ? extends InventoryComponent>
                    componentType =
                    InventoryComponent.getComponentTypeById(
                            SECTION_IDS.get(id)
                    );
            if (componentType == null) {
                return null;
            }
            InventoryComponent component =
                    store.getComponent(actor, componentType);
            if (component == null) {
                continue;
            }
            ItemContainer container = component.getInventory();
            sections.put(id, new Section(container, offset));
            offset = Math.addExact(offset, container.getCapacity());
        }
        if (InventoryComponent.BACKPACK_STORAGE_HOTBAR == null) {
            return null;
        }
        CombinedItemContainer combined = InventoryComponent.getCombined(
                store,
                actor,
                InventoryComponent.BACKPACK_STORAGE_HOTBAR
        );
        return new InventoryView(
                actor,
                store.getComponent(actor, receiptType),
                combined,
                Map.copyOf(sections)
        );
    }

    private enum SourceStatus {
        EXACT,
        PARTIAL,
        UNAVAILABLE,
        CONFLICT
    }

    private record SourcePlan(
            SourceStatus status,
            Map<Short, SlotMutation> mutations,
            Throwable cause
    ) {
        static SourcePlan exact(Map<Short, SlotMutation> mutations) {
            return new SourcePlan(
                    SourceStatus.EXACT, mutations, null
            );
        }

        static SourcePlan partial(Throwable cause) {
            return new SourcePlan(
                    SourceStatus.PARTIAL, Map.of(), cause
            );
        }

        static SourcePlan conflict(Throwable cause) {
            return new SourcePlan(
                    SourceStatus.CONFLICT, Map.of(), cause
            );
        }
    }

    private record SlotMutation(
            ItemStack original,
            ItemStack replacement,
            int quantity
    ) {
    }

    private record Section(ItemContainer container, int offset) {
    }

    private record InventoryView(
            Ref<EntityStore> actor,
            TameworkInventoryOperationReceiptsComponent receiptComponent,
            CombinedItemContainer combined,
            Map<String, Section> sections
    ) {
    }
}
