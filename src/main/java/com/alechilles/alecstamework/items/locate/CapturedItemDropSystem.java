package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.companion.item.CaptureItemFlows;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Kind;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks dropped captures at add/remove boundaries; movement is resolved only on Locate.
 *
 * <p>With {@code destroyedItems} set, it also ends the companion of a dropped capture item that
 * despawned or fell out of the world (spec 8.14). Only positive evidence counts: a REMOVE that is
 * not a player pickup, not a pickup-animation copy and not a {@code Pickup} interaction, whose
 * despawn time has passed or whose position is below the world. Any other removal, for example an
 * NPC picking the item up, leaves the record in the item for the owner's Recall or Forget.</p>
 */
public final class CapturedItemDropSystem extends RefSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final CapturedItemTracker tracker;
    @Nullable private final CaptureItemFlows destroyedItems;

    public CapturedItemDropSystem(CapturedItemTracker tracker) { this(tracker, null); }

    /** {@code destroyedItems} is null when the companion index is not ready; removals then only update the locator. */
    public CapturedItemDropSystem(CapturedItemTracker tracker, @Nullable CaptureItemFlows destroyedItems) {
        this.tracker = tracker;
        this.destroyedItems = destroyedItems;
    }

    @Override public Query<EntityStore> getQuery() { return ItemComponent.getComponentType(); }
    @Override public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        var item = buffer.getComponent(ref, ItemComponent.getComponentType());
        if (item != null && CapturedItemMetadata.read(item.getItemStack()) != null) {
            tracker.observeDrop(buffer, ref, store.getExternalData().getWorld().getName());
        }
    }
    @Override public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        if (reason == RemoveReason.REMOVE && destroyedItems != null) {
            noticeDestroyed(ref, store, buffer);
        }
        var uuid = buffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuid == null) return;
        var holder = CapturedItemTracker.entityHolder(Kind.DROPPED,
                store.getExternalData().getWorld().getName(), uuid.getUuid());
        if (reason == RemoveReason.UNLOAD) {
            var item = buffer.getComponent(ref, ItemComponent.getComponentType());
            if (item != null && CapturedItemMetadata.read(item.getItemStack()) != null) {
                tracker.observeDrop(buffer, ref, holder.worldName());
            }
            tracker.index().unload(holder);
        } else tracker.index().observe(holder, List.of(), System.currentTimeMillis());
    }

    /** Runs on the world thread inside the removal; the flow only changes the index. */
    private void noticeDestroyed(Ref<EntityStore> ref, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        ItemComponent item = buffer.getComponent(ref, ItemComponent.getComponentType());
        // A null stack is a merge into another item entity.
        if (item == null || item.isRemovedByPlayerPickup()) return;
        ItemStack stack = item.getItemStack();
        CaptureItemKeys.Ref key = CaptureItemKeys.readIndexItem(stack);
        if (key == null || buffer.getComponent(ref, PickupItemComponent.getComponentType()) != null
                || hasPickupInteraction(stack)) {
            return;
        }
        if (!despawned(ref, store, buffer) && !belowWorld(ref, buffer)) return;
        if (destroyedItems.itemDestroyed(key)) {
            LOGGER.at(Level.INFO).log("Capture item for companion %s was destroyed in world %s; the companion is gone",
                    key.profileId(), store.getExternalData().getWorld().getName());
        }
    }

    /** The engine removes an item with a Pickup interaction even when that interaction fails. */
    private static boolean hasPickupInteraction(ItemStack stack) {
        Item type = stack.getItem();
        return type != null && type.getInteractions() != null
                && type.getInteractions().get(InteractionType.Pickup) != null;
    }

    /** The despawn system removes an item once the world time is after its despawn instant. */
    private static boolean despawned(Ref<EntityStore> ref, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        DespawnComponent despawn = buffer.getComponent(ref, DespawnComponent.getComponentType());
        Instant at = despawn == null ? null : despawn.getDespawn();
        TimeResource time = store.getResource(TimeResource.getResourceType());
        return at != null && time != null && !at.isAfter(time.getNow());
    }

    private static boolean belowWorld(Ref<EntityStore> ref, CommandBuffer<EntityStore> buffer) {
        TransformComponent transform = buffer.getComponent(ref, TransformComponent.getComponentType());
        return transform != null && transform.getPosition().y < 0;
    }
}
