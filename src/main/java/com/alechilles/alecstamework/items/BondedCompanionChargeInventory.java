package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The player inventory a bonded panel action pays from (plan 6 R19): charge, then refund when the
 * action fails. It is a plain removal through {@link CommandReviveCostInventory} with no stored
 * receipt, so every charge is a new one and nothing is replayed; if the server stops between the
 * charge and the spawn, the payment is lost, as for the generic panel revive.
 *
 * <p>Built for one panel action on the player's world thread. Counting and the synchronous charge
 * answer only on that thread (0 and null elsewhere); the asynchronous charge hops to it. A refund
 * may be asked for on any thread: it finds the player's current world itself.</p>
 */
public final class BondedCompanionChargeInventory implements BondedCompanionActionContext.Inventory {
    private final World world;
    private final Store<EntityStore> store;
    private final Ref<EntityStore> playerRef;
    private final UUID playerUuid;

    BondedCompanionChargeInventory(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> playerRef, @Nonnull UUID playerUuid) {
        this.world = Objects.requireNonNull(world, "world");
        this.store = Objects.requireNonNull(store, "store");
        this.playerRef = Objects.requireNonNull(playerRef, "playerRef");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
    }

    @Override
    public int availableQuantity(String itemId) {
        if (itemId == null || itemId.isBlank() || !usableHere()) {
            return 0;
        }
        return CommandReviveCostInventory.count(store, playerRef, itemId);
    }

    @Override
    @Nullable
    public BondedCompanionActionContext.ChargeReceipt consumeExact(@Nonnull String operationId,
                                                                   @Nonnull String itemId, int quantity) {
        return charge(operationId, List.of(new BondedCompanionReviveCost(itemId, quantity)));
    }

    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt> consumeExactAsync(
            @Nonnull String operationId, @Nonnull String itemId, int quantity) {
        return consumeExactAsync(operationId, List.of(new BondedCompanionReviveCost(itemId, quantity)));
    }

    /** Takes the whole price or nothing. Completes with null when nothing was taken. */
    @Override
    public CompletionStage<BondedCompanionActionContext.ChargeReceipt> consumeExactAsync(
            @Nonnull String operationId, @Nonnull List<BondedCompanionReviveCost> costs) {
        if (world.isInThread()) {
            return CompletableFuture.completedFuture(charge(operationId, costs));
        }
        CompletableFuture<BondedCompanionActionContext.ChargeReceipt> charged = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    charged.complete(charge(operationId, costs));
                } catch (RuntimeException | LinkageError failure) {
                    charged.complete(null);
                }
            });
        } catch (RuntimeException notAccepting) {
            // The world no longer takes tasks, so nothing was charged.
            charged.complete(null);
        }
        return charged;
    }

    @Nullable
    private Receipt charge(String operationId, List<BondedCompanionReviveCost> costs) {
        if (operationId == null || costs == null || costs.isEmpty() || !usableHere()) {
            return null;
        }
        TwItemCostComponent[] price = new TwItemCostComponent[costs.size()];
        HashSet<String> itemIds = new HashSet<>();
        for (int i = 0; i < price.length; i++) {
            BondedCompanionReviveCost cost = costs.get(i);
            // The charge claims slots per item id, so one id listed twice would claim a slot twice.
            if (cost == null || !itemIds.add(cost.itemId())) {
                return null;
            }
            price[i] = new TwItemCostComponent(cost.itemId(), cost.quantity());
        }
        CommandReviveCostInventory.Charge charge = CommandReviveCostInventory.charge(store, playerRef, price);
        return charge.status() == CommandReviveCostInventory.Charge.Status.PAID
                ? new Receipt(operationId, costs, playerUuid, charge.paid()) : null;
    }

    /** The player is still the entity this inventory was built for, and this is its world thread. */
    private boolean usableHere() {
        return world.isInThread() && playerRef.isValid() && playerRef.getStore() == store;
    }

    /** One charge taken from the player. {@link #refund()} gives the items back once. */
    public static final class Receipt implements BondedCompanionActionContext.ChargeReceipt {
        private final String operationId;
        private final List<BondedCompanionReviveCost> costs;
        private final UUID playerUuid;
        private final List<ItemStack> paid;
        private final AtomicBoolean refunded = new AtomicBoolean();

        private Receipt(String operationId, List<BondedCompanionReviveCost> costs, UUID playerUuid,
                        List<ItemStack> paid) {
            this.operationId = operationId;
            this.costs = List.copyOf(costs);
            this.playerUuid = playerUuid;
            this.paid = List.copyOf(paid);
        }

        @Override
        @Nonnull
        public String operationId() {
            return operationId;
        }

        @Override
        @Nonnull
        public List<BondedCompanionReviveCost> costs() {
            return costs;
        }

        /**
         * Hands the paid items back on the player's current world thread; items that do not fit
         * are dropped at the player. Safe to call again: only the first call refunds.
         */
        @Override
        public boolean refund() {
            if (refunded.compareAndSet(false, true)) {
                CommandReviveCostInventory.refund(playerUuid, paid);
            }
            return true;
        }
    }
}
