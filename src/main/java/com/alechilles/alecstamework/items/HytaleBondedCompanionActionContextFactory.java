package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/** Freezes panel placement and exposes the current player's exact inventory. */
final class HytaleBondedCompanionActionContextFactory {
    private static final double DEFAULT_DISTANCE = 5D;
    private final CommandCompanionPlacementService placements =
            new CommandCompanionPlacementService();

    @Nullable
    BondedCompanionActionContext create(
            Player player, Store<EntityStore> store, String roleId,
            boolean placementRequired) {
        Ref<EntityStore> playerRef = player == null ? null : player.getReference();
        if (playerRef == null || !playerRef.isValid() || store == null
                || playerRef.getStore() != store || player.getUuid() == null) {
            return null;
        }
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.resolveEffectiveForRole(roleId);
        double distance = settings == null
                || !Double.isFinite(settings.getRecallSafeSpawnDistance())
                || settings.getRecallSafeSpawnDistance() <= 0D
                ? DEFAULT_DISTANCE : settings.getRecallSafeSpawnDistance();
        var placement = placementRequired
                ? placements.computeRestorationPlacement(
                        playerRef, store, distance, roleId, null)
                : null;
        String worldKey = player.getWorld() == null
                ? "bonded-context" : player.getWorld().getName();
        return new BondedCompanionActionContext(
                placement, new PlayerInventory(
                        playerRef, store, player.getUuid(), worldKey,
                        receiptType()));
    }

    @Nullable
    private ComponentType<EntityStore,
            TameworkInventoryOperationReceiptsComponent> receiptType() {
        try {
            Tamework plugin = Tamework.getInstance();
            return plugin == null
                    ? null : plugin.getInventoryOperationReceiptsComponentType();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private record PlayerInventory(
            Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            UUID ownerUuid,
            String worldKey,
            @Nullable ComponentType<EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType
    ) implements BondedCompanionActionContext.Inventory {
        @Override
        public int availableQuantity(String itemId) {
            CombinedItemContainer inventory = this.inventory();
            if (inventory == null || itemId == null) return 0;
            int available = 0;
            for (short slot = 0; slot < inventory.getCapacity(); slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (stack != null && itemId.equals(stack.getItemId())) {
                    available = Math.addExact(available, stack.getQuantity());
                }
            }
            return available;
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt findCharge(
                String operationId, String itemId, int quantity) {
            HytaleBondedCompanionChargeReceiptPlan receipt = receipt(
                    operationId, itemId, quantity);
            if (receipt == null || !receipt.recoverable()) return null;
            CombinedItemContainer inventory = inventory();
            return inventory == null
                    ? null : new ExactChargeReceipt(receipt, inventory, true);
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt consumeExact(
                String operationId, String itemId, int quantity) {
            HytaleBondedCompanionChargeReceiptPlan receipt = receipt(
                    operationId, itemId, quantity);
            if (receipt == null) return null;
            CombinedItemContainer inventory = this.inventory();
            if (inventory == null) return null;
            if (receipt.charged()) {
                return new ExactChargeReceipt(receipt, inventory, true);
            }
            if (!receipt.installPending()) return null;
            Map<Short, SlotCharge> charges = chargePlan(
                    inventory, itemId, quantity);
            if (charges == null) {
                receipt.release();
                return null;
            }
            AtomicBoolean mismatch = new AtomicBoolean();
            ListTransaction<ItemStackSlotTransaction> transaction =
                    inventory.replaceAll((slot, current) -> {
                        SlotCharge charge = charges.get(slot);
                        if (charge == null) return current;
                        if (!charge.original().equals(current)) {
                            mismatch.set(true);
                            return current;
                        }
                        return charge.replacement();
                    });
            if (transaction == null || !transaction.succeeded()
                    || mismatch.get()) {
                receipt.release();
                return null;
            }
            receipt.markCharged();
            return new ExactChargeReceipt(receipt, inventory, false);
        }

        @Nullable
        private HytaleBondedCompanionChargeReceiptPlan receipt(
                String operationId, String itemId, int quantity) {
            if (receiptType == null || operationId == null
                    || operationId.isBlank() || itemId == null
                    || itemId.isBlank() || quantity <= 0) return null;
            try {
                return new HytaleBondedCompanionChargeReceiptPlan(
                        playerRef, store, receiptType, ownerUuid, worldKey,
                        operationId, itemId, quantity);
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }

        private CombinedItemContainer inventory() {
            try {
                store.assertThread();
                return InventoryComponent.BACKPACK_STORAGE_HOTBAR == null
                        ? null : InventoryComponent.getCombined(
                                store, playerRef,
                                InventoryComponent.BACKPACK_STORAGE_HOTBAR);
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }

        private Map<Short, SlotCharge> chargePlan(
                CombinedItemContainer inventory, String itemId, int quantity) {
            HashMap<Short, SlotCharge> charges = new HashMap<>();
            int remaining = quantity;
            for (short slot = 0;
                    slot < inventory.getCapacity() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (stack == null || !itemId.equals(stack.getItemId())) continue;
                int remove = Math.min(remaining, stack.getQuantity());
                if (remove > 0) {
                    charges.put(slot, new SlotCharge(
                            stack, stack.withQuantity(
                                    stack.getQuantity() - remove)));
                }
                remaining -= remove;
            }
            return remaining == 0 ? Map.copyOf(charges) : null;
        }

    }

    private record SlotCharge(ItemStack original, ItemStack replacement) {}

    private static final class ExactChargeReceipt implements
            BondedCompanionActionContext.ChargeReceipt {
        private final HytaleBondedCompanionChargeReceiptPlan receipt;
        private final CombinedItemContainer inventory;
        private final boolean replayed;
        private boolean refunded;
        private boolean completed;

        private ExactChargeReceipt(
                HytaleBondedCompanionChargeReceiptPlan receipt,
                CombinedItemContainer inventory, boolean replayed) {
            this.receipt = receipt;
            this.inventory = inventory;
            this.replayed = replayed;
        }

        @Override
        public String operationId() {
            return receipt.operationKey();
        }

        @Override
        public boolean replayed() {
            return replayed;
        }

        @Override
        public boolean compensationPending() {
            return receipt.compensating();
        }

        @Override
        public synchronized boolean refund() {
            if (refunded) return true;
            if (!receipt.refund(inventory)) return false;
            refunded = true;
            return true;
        }

        @Override
        public synchronized boolean complete() {
            if (completed || refunded) return true;
            if (!receipt.release()) return false;
            completed = true;
            return true;
        }
    }
}
