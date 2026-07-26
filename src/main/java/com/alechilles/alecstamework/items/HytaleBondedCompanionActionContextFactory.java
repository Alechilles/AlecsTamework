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
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
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
        private static final BondedCompanionChargeCoordinator CHARGES =
                new BondedCompanionChargeCoordinator();

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
            CombinedItemContainer inventory = inventory();
            if (receipt == null || inventory == null) return null;
            ChargeAttempt attempt = new ChargeAttempt(
                    receipt, inventory, itemId, quantity);
            BondedCompanionChargeCoordinator.Outcome outcome =
                    CHARGES.find(attempt);
            return chargeReceipt(receipt, inventory, outcome);
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt consumeExact(
                String operationId, String itemId, int quantity) {
            HytaleBondedCompanionChargeReceiptPlan receipt = receipt(
                    operationId, itemId, quantity);
            if (receipt == null) return null;
            CombinedItemContainer inventory = this.inventory();
            if (inventory == null) return null;
            ChargeAttempt attempt = new ChargeAttempt(
                    receipt, inventory, itemId, quantity);
            BondedCompanionChargeCoordinator.Outcome outcome =
                    CHARGES.consume(attempt);
            return chargeReceipt(receipt, inventory, outcome);
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

        @Nullable
        private ExactChargeReceipt chargeReceipt(
                HytaleBondedCompanionChargeReceiptPlan receipt,
                CombinedItemContainer inventory,
                BondedCompanionChargeCoordinator.Outcome outcome) {
            return switch (outcome.status()) {
                case UNAVAILABLE -> null;
                case CHARGED -> new ExactChargeReceipt(
                        receipt, inventory, outcome.replayed(), false);
                case RECOVERY_PENDING -> new ExactChargeReceipt(
                        receipt, inventory, true, true);
            };
        }
    }

    private static final class ChargeAttempt implements
            BondedCompanionChargeCoordinator.Attempt {
        private final HytaleBondedCompanionChargeReceiptPlan receipt;
        private final CombinedItemContainer inventory;
        private final String itemId;
        private final int quantity;

        private ChargeAttempt(HytaleBondedCompanionChargeReceiptPlan receipt,
                              CombinedItemContainer inventory,
                              String itemId, int quantity) {
            this.receipt = receipt;
            this.inventory = inventory;
            this.itemId = itemId;
            this.quantity = quantity;
        }

        @Override
        public BondedCompanionChargeCoordinator.State state() {
            try {
                return receipt.state(availableQuantity());
            } catch (RuntimeException | LinkageError failure) {
                return BondedCompanionChargeCoordinator.State.CONFLICT;
            }
        }

        @Override
        public boolean installPending() {
            try {
                return receipt.installPending(availableQuantity());
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        @Override
        public BondedCompanionChargeCoordinator.DebitResult debitAtomically() {
            int before = availableQuantity();
            ItemStackTransaction transaction = null;
            try {
                transaction = inventory.removeItemStack(
                        new ItemStack(itemId, quantity), true, false);
            } catch (RuntimeException | LinkageError ignored) {
                // The measured post-state below decides whether recovery is needed.
            }
            int debited = before - availableQuantity();
            if (debited == 0) {
                return BondedCompanionChargeCoordinator.DebitResult.NONE;
            }
            return debited == quantity && transaction != null
                    && transaction.succeeded()
                    && ItemStack.isEmpty(transaction.getRemainder())
                    ? BondedCompanionChargeCoordinator.DebitResult.EXACT
                    : BondedCompanionChargeCoordinator.DebitResult.PARTIAL;
        }

        @Override
        public boolean markCharged() {
            try {
                return receipt.markCharged(availableQuantity());
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        @Override
        public boolean refund() {
            try {
                return receipt.refund(inventory);
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        @Override
        public boolean releasePrepared() {
            try {
                return receipt.releasePrepared(availableQuantity());
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        private int availableQuantity() {
            int available = 0;
            for (short slot = 0; slot < inventory.getCapacity(); slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (stack != null && itemId.equals(stack.getItemId())) {
                    available = Math.addExact(available, stack.getQuantity());
                }
            }
            return available;
        }
    }

    private static final class ExactChargeReceipt implements
            BondedCompanionActionContext.ChargeReceipt {
        private final HytaleBondedCompanionChargeReceiptPlan receipt;
        private final CombinedItemContainer inventory;
        private final boolean replayed;
        private final boolean recoveryPending;
        private boolean refunded;
        private boolean completed;

        private ExactChargeReceipt(
                HytaleBondedCompanionChargeReceiptPlan receipt,
                CombinedItemContainer inventory, boolean replayed,
                boolean recoveryPending) {
            this.receipt = receipt;
            this.inventory = inventory;
            this.replayed = replayed;
            this.recoveryPending = recoveryPending;
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
            return recoveryPending;
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
