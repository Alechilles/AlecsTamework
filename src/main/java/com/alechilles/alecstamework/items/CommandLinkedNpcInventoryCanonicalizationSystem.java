package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ClearTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lazily canonicalizes command records when a linked command item moves through player inventory.
 *
 * <p>The inventory event itself runs during ECS processing. This system therefore captures only
 * the player UUID, coalesces same-tick changes, and queues the identity read plus inventory write
 * through {@link World#execute(Runnable)}. Canonicalization writes enqueue a later inventory event;
 * that follow-up pass is harmless because an already canonical stack is never rewritten.</p>
 */
public final class CommandLinkedNpcInventoryCanonicalizationSystem
        extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
    private final CommandItemFeatureHandler featureHandler;
    private final Set<UUID> queuedPlayers = ConcurrentHashMap.newKeySet();

    public CommandLinkedNpcInventoryCanonicalizationSystem(
            @Nonnull CommandItemFeatureHandler featureHandler) {
        super(InventoryChangeEvent.class);
        this.featureHandler = Objects.requireNonNull(featureHandler, "featureHandler");
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull InventoryChangeEvent event) {
        if (!isCanonicalInventory(event.getComponentType())
                || !affectsLinkedCommandItem(event.getTransaction())) {
            return;
        }
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        UUID playerUuid = player != null ? player.getUuid() : null;
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (playerUuid == null || world == null || !queuedPlayers.add(playerUuid)) {
            return;
        }
        try {
            world.execute(() -> canonicalizeQueuedPlayer(world, playerUuid));
        } catch (RuntimeException exception) {
            queuedPlayers.remove(playerUuid);
        }
    }

    private void canonicalizeQueuedPlayer(@Nonnull World world, @Nonnull UUID playerUuid) {
        try {
            featureHandler.canonicalizePlayerCommandInventory(world, playerUuid);
        } finally {
            queuedPlayers.remove(playerUuid);
        }
    }

    private boolean isCanonicalInventory(
            @Nullable ComponentType<EntityStore, ? extends InventoryComponent> componentType) {
        return componentType == InventoryComponent.Hotbar.getComponentType()
                || componentType == InventoryComponent.Storage.getComponentType()
                || componentType == InventoryComponent.Backpack.getComponentType();
    }

    private boolean affectsLinkedCommandItem(@Nullable Transaction transaction) {
        if (transaction == null || !transaction.succeeded()) {
            return false;
        }
        if (transaction instanceof SlotTransaction slot) {
            return hasLinkedRecords(slot.getSlotBefore())
                    || hasLinkedRecords(slot.getSlotAfter())
                    || hasLinkedRecords(slot.getOutput());
        }
        if (transaction instanceof MoveTransaction<?> move) {
            return affectsLinkedCommandItem(move.getRemoveTransaction())
                    || affectsLinkedCommandItem(move.getAddTransaction());
        }
        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            if (hasLinkedRecords(itemStackTransaction.getQuery())) {
                return true;
            }
            for (SlotTransaction slot : itemStackTransaction.getSlotTransactions()) {
                if (affectsLinkedCommandItem(slot)) {
                    return true;
                }
            }
            return false;
        }
        if (transaction instanceof ListTransaction<?> listTransaction) {
            for (Transaction nested : listTransaction.getList()) {
                if (affectsLinkedCommandItem(nested)) {
                    return true;
                }
            }
            return false;
        }
        if (transaction instanceof ClearTransaction clearTransaction) {
            for (ItemStack stack : clearTransaction.getItems()) {
                if (hasLinkedRecords(stack)) {
                    return true;
                }
            }
            return false;
        }
        // Unknown successful transaction shapes fail open so an engine update cannot skip repair.
        return true;
    }

    private boolean hasLinkedRecords(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String encoded = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING);
        return encoded != null && !encoded.isBlank();
    }
}
