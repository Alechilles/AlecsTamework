package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
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
                || playerRef.getStore() != store) return null;
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
        return new BondedCompanionActionContext(
                placement, new PlayerInventory(playerRef, store));
    }

    private record PlayerInventory(
            Ref<EntityStore> playerRef,
            Store<EntityStore> store
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
        public BondedCompanionActionContext.ChargeReceipt consumeExact(
                String operationId, String itemId, int quantity) {
            if (operationId == null || operationId.isBlank()
                    || itemId == null || itemId.isBlank() || quantity <= 0) {
                return null;
            }
            CombinedItemContainer inventory = this.inventory();
            if (inventory == null) return null;
            Map<Short, SlotCharge> charges = chargePlan(
                    inventory, itemId, quantity);
            if (charges == null) return null;
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
                    || mismatch.get() || !postStateMatches(inventory, charges)) {
                return null;
            }
            return new ExactChargeReceipt(operationId, this, charges);
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

        private boolean postStateMatches(
                CombinedItemContainer inventory,
                Map<Short, SlotCharge> charges) {
            for (Map.Entry<Short, SlotCharge> entry : charges.entrySet()) {
                if (!java.util.Objects.equals(entry.getValue().replacement(),
                        inventory.getItemStack(entry.getKey()))) return false;
            }
            return true;
        }

        private boolean refund(Map<Short, SlotCharge> charges) {
            CombinedItemContainer inventory = inventory();
            if (inventory == null) return false;
            AtomicBoolean mismatch = new AtomicBoolean();
            ListTransaction<ItemStackSlotTransaction> transaction =
                    inventory.replaceAll((slot, current) -> {
                        SlotCharge charge = charges.get(slot);
                        if (charge == null) return current;
                        if (!java.util.Objects.equals(
                                charge.replacement(), current)) {
                            mismatch.set(true);
                            return current;
                        }
                        return charge.original();
                    });
            return transaction != null && transaction.succeeded()
                    && !mismatch.get();
        }
    }

    private record SlotCharge(ItemStack original, ItemStack replacement) {}

    private static final class ExactChargeReceipt implements
            BondedCompanionActionContext.ChargeReceipt {
        private final PlayerInventory inventory;
        private final Map<Short, SlotCharge> charges;
        private final String operationId;
        private boolean refunded;

        private ExactChargeReceipt(String operationId, PlayerInventory inventory,
                                   Map<Short, SlotCharge> charges) {
            this.operationId = operationId;
            this.inventory = inventory;
            this.charges = charges;
        }

        @Override
        public String operationId() {
            return operationId;
        }

        @Override
        public synchronized boolean refund() {
            if (refunded) return true;
            if (!inventory.refund(charges)) return false;
            refunded = true;
            return true;
        }
    }
}
