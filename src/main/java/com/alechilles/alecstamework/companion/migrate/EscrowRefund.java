package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.items.components.TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.items.components.TameworkBondedReviveEscrowComponent.Phase;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Settles the retired bonded revive escrow that 4.x left on a player (plan 7 R16, spec 12.4).
 * The escrow is a hidden inventory holding the items of one revive payment. Nothing in 5.0 reads
 * it, so when its player enters a world it is settled and removed:
 *
 * <ul>
 *   <li>phase COMMITTED or REFUNDED: the revive was paid for, or the items were already given
 *       back, so whatever is left in it is discarded;</li>
 *   <li>any other phase: the items go back to the player's inventory and what does not fit is
 *       dropped at the player's feet.</li>
 * </ul>
 *
 * <p>The system's query is the escrow type, so only players that still carry one are visited. The
 * add callback only queues a task on the same world thread with the player's UUID. The task
 * resolves the player again and does everything in one step: it empties the escrow, hands the
 * stacks out, drops the overflow and removes the component. Emptying the escrow in that step is
 * the guard against a second hand-out: a second run (join, then a world change) finds no component
 * or an empty one. If the overflow cannot be dropped it is put back into the escrow and the
 * component stays, so the next join retries only that remainder.</p>
 */
public final class EscrowRefund {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Suffixed with {@code .one} or {@code .many} by the returned quantity. */
    static final String RETURNED_KEY = "server.tamework.companions.escrow.returned";

    private final ComponentType<EntityStore, TameworkBondedReviveEscrowComponent> escrowType;

    public EscrowRefund(@Nonnull ComponentType<EntityStore, TameworkBondedReviveEscrowComponent> escrowType) {
        this.escrowType = Objects.requireNonNull(escrowType, "escrowType");
    }

    /**
     * What one settlement did. {@code returned}, {@code dropped} and {@code discarded} are item
     * quantities. {@code kept} holds what could be neither given nor dropped; it goes back into
     * the escrow for the next attempt.
     */
    public record Settlement<T>(int returned, int dropped, int discarded, @Nonnull List<T> kept) {
    }

    /** False when the payment was spent on a revive or already given back. */
    static boolean returnsItems(@Nonnull Phase phase) {
        return phase != Phase.COMMITTED && phase != Phase.REFUNDED;
    }

    /**
     * Decides what happens to the stacks taken out of an escrow and applies it through the two
     * callbacks. {@code give} puts one stack in the inventory and returns what did not fit, or
     * null when all of it fit; a stack whose hand-out throws counts as not given. {@code drop}
     * drops all the overflow at once and returns true only when every stack was dropped; when it
     * returns false or throws, the overflow is kept.
     */
    @Nonnull
    static <T> Settlement<T> settle(@Nonnull Phase phase, @Nonnull List<T> stacks,
                                    @Nonnull ToIntFunction<T> quantity, @Nonnull UnaryOperator<T> give,
                                    @Nonnull Predicate<List<T>> drop) {
        if (!returnsItems(phase)) {
            return new Settlement<>(0, 0, total(stacks, quantity), List.of());
        }
        int returned = 0;
        List<T> overflow = new ArrayList<>();
        for (T stack : stacks) {
            int amount = quantity.applyAsInt(stack);
            T left;
            try {
                left = give.apply(stack);
            } catch (RuntimeException | LinkageError failure) {
                left = stack;
            }
            if (left == null) {
                returned += amount;
            } else {
                returned += Math.max(0, amount - quantity.applyAsInt(left));
                overflow.add(left);
            }
        }
        if (overflow.isEmpty()) {
            return new Settlement<>(returned, 0, 0, List.of());
        }
        boolean dropped;
        try {
            dropped = drop.test(List.copyOf(overflow));
        } catch (RuntimeException | LinkageError failure) {
            dropped = false;
        }
        return dropped ? new Settlement<>(returned, total(overflow, quantity), 0, List.of())
                : new Settlement<>(returned, 0, 0, List.copyOf(overflow));
    }

    private static <T> int total(List<T> stacks, ToIntFunction<T> quantity) {
        int total = 0;
        for (T stack : stacks) {
            total += quantity.applyAsInt(stack);
        }
        return total;
    }

    /** The entity system to register; it sees a player with an escrow enter a world. */
    @Nonnull
    public RefSystem<EntityStore> system() {
        return new OnPlayerAdded(this);
    }

    /** World thread only. Does nothing when the player left this world or has no escrow. */
    private void refundOnWorldThread(World world, UUID playerUuid) {
        Store<EntityStore> store = world.getEntityStore() == null ? null : world.getEntityStore().getStore();
        Ref<EntityStore> ref = store == null ? null : world.getEntityRef(playerUuid);
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return;
        }
        TameworkBondedReviveEscrowComponent escrow = store.getComponent(ref, escrowType);
        if (escrow == null) {
            return;
        }
        Phase phase = escrow.phase();
        ItemContainer held = escrow.getInventory();
        List<ItemStack> stacks = held == null ? List.of() : held.removeAllItemStacks();
        Settlement<ItemStack> settlement = settle(phase, stacks, ItemStack::getQuantity, stack -> {
            ItemStackTransaction given = Player.giveItem(stack, ref, store);
            ItemStack left = given == null ? stack : given.getRemainder();
            return ItemStack.isEmpty(left) ? null : left;
        }, overflow -> drop(store, ref, overflow));
        int keptQuantity = 0;
        for (ItemStack stack : settlement.kept()) {
            keptQuantity += stack.getQuantity();
            ItemStackTransaction back = held.addItemStack(stack);
            if (back == null || !ItemStack.isEmpty(back.getRemainder())) {
                LOGGER.at(Level.WARNING).log("Could not put %d x %s back into the old revive escrow of player %s; "
                        + "these items are lost", stack.getQuantity(), stack.getItemId(), playerUuid);
            }
        }
        if (settlement.kept().isEmpty()) {
            // The escrow is already empty; if this removal fails, an empty escrow stays and is harmless.
            store.tryRemoveComponent(ref, escrowType);
        } else {
            LOGGER.at(Level.WARNING).log("Could not drop %d old revive escrow items at player %s; they stay in "
                    + "the escrow and are returned on the next join", keptQuantity, playerUuid);
        }
        LOGGER.at(Level.INFO).log("Settled the old revive escrow of player %s (phase %s): %d items returned, "
                        + "%d dropped at the player, %d discarded, %d kept for the next join",
                playerUuid, phase, settlement.returned(), settlement.dropped(), settlement.discarded(), keptQuantity);
        int back = settlement.returned() + settlement.dropped();
        PlayerRef player = back <= 0 ? null : store.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            player.sendMessage(Message.translation(RETURNED_KEY + (back == 1 ? ".one" : ".many"))
                    .param("0", String.valueOf(back)));
        }
    }

    /**
     * Drops the overflow at the player's feet. True only when a drop was spawned for every stack;
     * otherwise nothing is spawned.
     */
    private static boolean drop(Store<EntityStore> store, Ref<EntityStore> ref, List<ItemStack> overflow) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return false;
        }
        Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(store, overflow,
                new Vector3d(transform.getPosition()), Rotation3f.IDENTITY);
        if (drops.length != overflow.size()) {
            return false;
        }
        store.addEntities(drops, AddReason.SPAWN);
        return true;
    }

    /** Sees an entity that carries the retired escrow enter a store; only players ever do. */
    private static final class OnPlayerAdded extends RefSystem<EntityStore> {
        private final EscrowRefund refund;

        private OnPlayerAdded(EscrowRefund refund) {
            this.refund = refund;
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            UUIDComponent uuid = store.getComponent(ref, UUIDComponent.getComponentType());
            World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
            UUID playerUuid = uuid == null ? null : uuid.getUuid();
            if (world == null || playerUuid == null) {
                return;
            }
            // Giving and dropping items add entities and change inventories, so they run as a
            // world task after this add has finished. Only the UUID crosses over.
            try {
                world.execute(() -> {
                    try {
                        refund.refundOnWorldThread(world, playerUuid);
                    } catch (RuntimeException | LinkageError failure) {
                        LOGGER.at(Level.WARNING).withCause(failure).log(
                                "Could not settle the old revive escrow of player %s", playerUuid);
                    }
                });
            } catch (RuntimeException notAccepting) {
                // The world stopped; the escrow stays and is settled on the next join.
            }
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        }

        @Override
        @Nullable
        public Query<EntityStore> getQuery() {
            return refund.escrowType;
        }
    }
}
