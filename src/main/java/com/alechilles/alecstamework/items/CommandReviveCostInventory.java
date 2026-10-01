package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Takes and returns the exact item cost of a panel revive.
 *
 * <p>The charge is a plain inventory removal with no stored receipt: if the server stops between
 * the charge and the spawn, the payment is lost. That is accepted for the panel revive. Every
 * method that touches the inventory runs on the owning world thread.</p>
 */
final class CommandReviveCostInventory {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int MAX_WORLD_HANDOFFS = 3;

    private CommandReviveCostInventory() {
    }

    /** The outcome of {@link #charge}. {@code paid} is set only when {@code status} is PAID. */
    record Charge(@Nonnull Status status, @Nonnull List<ItemStack> paid) {
        enum Status {
            PAID,
            INSUFFICIENT,
            UNAVAILABLE
        }
    }

    /**
     * Removes every cost item, or nothing: the stacks are chosen first, so a shortage leaves the
     * inventory untouched. Items match by exact item id across backpack, storage and hotbar.
     */
    @Nonnull
    static Charge charge(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull TwItemCostComponent[] costs
    ) {
        CombinedItemContainer inventory = InventoryComponent.BACKPACK_STORAGE_HOTBAR == null
                ? null
                : InventoryComponent.getCombined(
                        store, playerRef, InventoryComponent.BACKPACK_STORAGE_HOTBAR);
        if (inventory == null) {
            return new Charge(Charge.Status.UNAVAILABLE, List.of());
        }
        // Cost item ids are unique (validated by the config), so no slot is claimed twice.
        List<Take> takes = new ArrayList<>();
        for (TwItemCostComponent cost : costs) {
            int missing = cost.getQuantity();
            for (short slot = 0; slot < inventory.getCapacity() && missing > 0; slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (ItemStack.isEmpty(stack) || !cost.getItemId().equals(stack.getItemId())) {
                    continue;
                }
                int quantity = Math.min(missing, stack.getQuantity());
                takes.add(new Take(slot, stack.withQuantity(quantity)));
                missing -= quantity;
            }
            if (missing > 0) {
                return new Charge(Charge.Status.INSUFFICIENT, List.of());
            }
        }
        List<ItemStack> paid = new ArrayList<>();
        for (Take take : takes) {
            var transaction = inventory.removeItemStackFromSlot(take.slot(), take.stack().getQuantity());
            if (transaction == null || !transaction.succeeded()) {
                give(playerRef, store, paid);
                return new Charge(Charge.Status.UNAVAILABLE, List.of());
            }
            paid.add(take.stack());
        }
        return new Charge(Charge.Status.PAID, List.copyOf(paid));
    }

    /**
     * Gives paid items back to the player after a failed revive. Items that do not fit are dropped
     * at the player's position. The player is resolved by id inside the world's executor, in
     * whichever world they are in now. A player who left the server cannot be refunded; that is
     * logged with the item ids.
     */
    static void refund(@Nonnull UUID playerUuid, @Nonnull List<ItemStack> paid) {
        if (paid.isEmpty()) {
            return;
        }
        dispatchRefund(playerUuid, paid, 0);
    }

    private static void dispatchRefund(UUID playerUuid, List<ItemStack> paid, int handoffs) {
        World world = currentWorld(playerUuid);
        if (world == null) {
            LOGGER.at(Level.WARNING).log("Revive refund lost: player " + playerUuid
                    + " is not in a live world. Items: " + describe(paid));
            return;
        }
        try {
            world.execute(() -> {
                Ref<EntityStore> ref = world.getEntityRef(playerUuid);
                if (ref == null || !ref.isValid()) {
                    // The player changed world between lookup and execution.
                    if (handoffs < MAX_WORLD_HANDOFFS) {
                        dispatchRefund(playerUuid, paid, handoffs + 1);
                    } else {
                        LOGGER.at(Level.WARNING).log("Revive refund lost: player " + playerUuid
                                + " kept changing world. Items: " + describe(paid));
                    }
                    return;
                }
                try {
                    give(ref, ref.getStore(), paid);
                } catch (RuntimeException failure) {
                    LOGGER.at(Level.SEVERE).withCause(failure).log("Revive refund failed for player "
                            + playerUuid + ". Items: " + describe(paid));
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Revive refund could not be scheduled for player "
                    + playerUuid + ". Items: " + describe(paid));
        }
    }

    @Nullable
    private static World currentWorld(UUID playerUuid) {
        Universe universe = Universe.get();
        PlayerRef player = universe == null ? null : universe.getPlayer(playerUuid);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() == null
                || ref.getStore().getExternalData() == null) {
            return null;
        }
        World world = ref.getStore().getExternalData().getWorld();
        return world != null && world.isAlive() ? world : null;
    }

    private static void give(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack remainder = Player.giveItem(stack, ref, accessor).getRemainder();
            if (!ItemStack.isEmpty(remainder)) {
                ItemUtils.dropItem(ref, remainder, accessor);
            }
        }
    }

    private static String describe(List<ItemStack> stacks) {
        StringBuilder out = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(stack.getItemId()).append(" x").append(stack.getQuantity());
        }
        return out.toString();
    }

    private record Take(short slot, ItemStack stack) {
    }
}
