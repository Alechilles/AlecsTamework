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
            Player player, Store<EntityStore> store, String roleId) {
        Ref<EntityStore> playerRef = player == null ? null : player.getReference();
        if (playerRef == null || !playerRef.isValid() || store == null
                || playerRef.getStore() != store) return null;
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.resolveEffectiveForRole(roleId);
        double distance = settings == null
                || !Double.isFinite(settings.getRecallSafeSpawnDistance())
                || settings.getRecallSafeSpawnDistance() <= 0D
                ? DEFAULT_DISTANCE : settings.getRecallSafeSpawnDistance();
        var placement = placements.computeRestorationPlacement(
                playerRef, store, distance, roleId, null);
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
        public boolean consumeExact(String itemId, int quantity) {
            if (itemId == null || itemId.isBlank() || quantity <= 0) return false;
            CombinedItemContainer inventory = this.inventory();
            if (inventory == null) return false;
            Map<Short, Integer> removals = removalPlan(
                    inventory, itemId, quantity);
            if (removals == null) return false;
            AtomicBoolean mismatch = new AtomicBoolean();
            ListTransaction<ItemStackSlotTransaction> transaction =
                    inventory.replaceAll((slot, current) -> {
                        Integer remove = removals.get(slot);
                        if (remove == null) return current;
                        if (current == null || !itemId.equals(current.getItemId())
                                || current.getQuantity() < remove) {
                            mismatch.set(true);
                            return current;
                        }
                        return current.withQuantity(current.getQuantity() - remove);
                    });
            return transaction != null && transaction.succeeded()
                    && !mismatch.get();
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

        private Map<Short, Integer> removalPlan(
                CombinedItemContainer inventory, String itemId, int quantity) {
            HashMap<Short, Integer> removals = new HashMap<>();
            int remaining = quantity;
            for (short slot = 0;
                    slot < inventory.getCapacity() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (stack == null || !itemId.equals(stack.getItemId())) continue;
                int remove = Math.min(remaining, stack.getQuantity());
                if (remove > 0) removals.put(slot, remove);
                remaining -= remove;
            }
            return remaining == 0 ? Map.copyOf(removals) : null;
        }
    }
}
