package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Kind;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nonnull;

/** Tracks dropped captures at add/remove boundaries; movement is resolved only on Locate. */
public final class CapturedItemDropSystem extends RefSystem<EntityStore> {
    private final CapturedItemTracker tracker;
    public CapturedItemDropSystem(CapturedItemTracker tracker) { this.tracker = tracker; }
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
}
