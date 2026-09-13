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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One lightweight listener per loaded standard storage block, removed on unload/shutdown. */
public final class CapturedItemContainerSystem extends RefSystem<ChunkStore> {
    // Vanilla may replace the container while resizing it on load. Listen to the final instance.
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, ItemContainerSystems.OnAddedOrRemoved.class));
    private final CapturedItemTracker tracker;
    private final Map<BlockEntityKey, Registration> registrations
            = new ConcurrentHashMap<>();
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
        Holder holder = holder(ref, store, buffer);
        var block = buffer.getComponent(ref, ItemContainerBlock.getComponentType());
        if (holder == null || block == null || block.getItemContainer() == null) return;
        BlockEntityKey key = new BlockEntityKey(holder.worldName(), ref.getIndex());
        var previous = registrations.remove(key);
        if (previous != null) previous.event().unregister();
        registrations.put(key, new Registration(holder, block.getItemContainer().registerChangeEvent(
                EventPriority.LAST, event -> {
                    if (CapturedItemMetadata.affectsCapture(event.transaction())) tracker.queue(holder);
                })));
        tracker.observeContainer(holder, block.getItemContainer());
    }
    @Override public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> buffer) {
        var registration = registrations.remove(new BlockEntityKey(store.getExternalData().getWorld().getName(), ref.getIndex()));
        if (registration == null) return;
        registration.event().unregister();
        Holder holder = registration.holder();
        if (reason == RemoveReason.UNLOAD) tracker.index().unload(holder);
        else tracker.index().observe(holder, List.of(), System.currentTimeMillis());
    }
    private record BlockEntityKey(String world, int index) { }
    private record Registration(Holder holder, EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> event) { }
    @Nullable private Holder holder(Ref<ChunkStore> ref, Store<ChunkStore> store, CommandBuffer<ChunkStore> buffer) {
        var info = buffer.getComponent(ref, BlockModule.get().getBlockStateInfoComponentType());
        var location = HytaleBlockStateAccess.resolve(store, info);
        if (location == null) return null;
        var blockType = location.chunk().getBlockType(location.x(), location.y(), location.z());
        return new Holder(Kind.CONTAINER, store.getExternalData().getWorld().getName(),
                location.x() + "," + location.y() + "," + location.z(), blockType == null ? "" : blockType.getId(),
                location.x(), location.y(), location.z());
    }
}
