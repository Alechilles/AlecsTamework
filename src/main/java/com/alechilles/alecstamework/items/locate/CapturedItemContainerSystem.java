package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.compat.HytaleBlockStateAccess;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.*;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Holder;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.block.system.ItemContainerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One lightweight listener per loaded standard storage block, removed on unload/shutdown. */
public final class CapturedItemContainerSystem extends RefSystem<ChunkStore> {
    // Vanilla may replace the container while resizing it on load. Listen to the final instance.
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, ItemContainerSystems.OnAddedOrRemoved.class));
    private final CapturedItemTracker tracker;
    // Keyed by the Ref itself: its index shifts whenever another chunk entity is removed.
    private final Map<Ref<ChunkStore>, Registration> registrations
            = new ConcurrentHashMap<>();
    private final Set<Store<ChunkStore>> seenStores = Collections.newSetFromMap(new WeakHashMap<>());
    public CapturedItemContainerSystem(CapturedItemTracker tracker) {
        this.tracker = tracker;
        tracker.onClose(() -> {
            registrations.values().forEach(value -> value.event().unregister());
            registrations.clear();
        });
    }
    @Override public Query<ChunkStore> getQuery() {
        return Query.and(ItemContainerBlock.getComponentType(), BlockModule.get().getBlockStateInfoComponentType());
    }
    @Override public Set<Dependency<ChunkStore>> getDependencies() { return DEPENDENCIES; }
    @Override public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason,
            @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> buffer) {
        forgetRemovedWorlds(store);
        Holder holder = holder(ref, store, buffer);
        var block = buffer.getComponent(ref, ItemContainerBlock.getComponentType());
        if (holder == null || block == null || block.getItemContainer() == null) return;
        var previous = registrations.remove(ref);
        if (previous != null) previous.event().unregister();
        registrations.put(ref, new Registration(holder, block.getItemContainer().registerChangeEvent(
                EventPriority.LAST, event -> {
                    if (CapturedItemMetadata.affectsCapture(event.transaction())) tracker.queue(holder);
                })));
        tracker.observeContainer(holder, block.getItemContainer());
    }
    @Override public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> buffer) {
        var registration = registrations.remove(ref);
        if (registration == null) return;
        registration.event().unregister();
        Holder holder = registration.holder();
        if (reason == RemoveReason.UNLOAD) tracker.index().unload(holder);
        else tracker.index().observe(holder, List.of(), System.currentTimeMillis());
    }
    /**
     * A removed world shuts its store down without calling onEntityRemove, and each key holds its
     * store, so drop the entries of shut-down stores whenever a new store first adds a container.
     */
    private void forgetRemovedWorlds(Store<ChunkStore> store) {
        synchronized (seenStores) {
            if (!seenStores.add(store)) return;
            seenStores.removeIf(Store::isShutdown);
        }
        registrations.entrySet().removeIf(entry -> {
            if (!entry.getKey().getStore().isShutdown()) return false;
            entry.getValue().event().unregister();
            tracker.index().unload(entry.getValue().holder());
            return true;
        });
    }
    private record Registration(Holder holder, EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> event) { }
    @Nullable private Holder holder(Ref<ChunkStore> ref, Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer) {
        var info = buffer.getComponent(ref, BlockModule.get().getBlockStateInfoComponentType());
        var location = HytaleBlockStateAccess.resolve(store, info);
        if (location == null) return null;
        var blockType = HytaleBlockStateAccess.blockTypeAt(
                location.chunk(), location.x(), location.y(), location.z());
        return new Holder(Kind.CONTAINER, store.getExternalData().getWorld().getName(),
                location.x() + "," + location.y() + "," + location.z(), blockType == null ? "" : blockType.getId(),
                location.x(), location.y(), location.z());
    }
}
