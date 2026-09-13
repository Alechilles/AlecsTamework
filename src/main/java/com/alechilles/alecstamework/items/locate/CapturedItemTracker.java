package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.*;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Holder;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Optional item sightings, never companion lifecycle authority. All live reads run on the owning
 * world thread. Initial loads and capture-related changes observe only the affected holder; no
 * periodic live scan runs. A bounded cache is saved off-thread at most once a minute when dirty.
 */
public final class CapturedItemTracker implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(CapturedItemTracker.class.getName());
    private final CapturedItemLocationIndex index = new CapturedItemLocationIndex();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final List<Runnable> cleanup = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private CapturedItemLocationCache cache;
    private ScheduledExecutorService saver;
    private long savedRevision;

    public CapturedItemLocationIndex index() { return index; }

    /** Called once when the owning runtime module activates, before observations begin. */
    public synchronized void start(Path file) {
        if (cache != null || closed) return;
        cache = new CapturedItemLocationCache(file, index);
        try { cache.load(); }
        catch (IOException failure) { LOGGER.warning("Capture item location cache could not be read: " + failure.getMessage()); }
        savedRevision = index.revision();
        saver = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Tamework-capture-location-cache");
            thread.setDaemon(true);
            return thread;
        });
        saver.scheduleWithFixedDelay(this::saveIfDirty, 60, 60, TimeUnit.SECONDS);
    }

    public void onClose(Runnable action) { cleanup.add(action); }

    private synchronized void saveIfDirty() {
        long revision = index.revision();
        if (cache == null || revision == savedRevision) return;
        try { cache.save(); savedRevision = revision; }
        catch (IOException failure) { LOGGER.warning("Capture item location cache could not be saved: " + failure.getMessage()); }
    }

    /** Coalesces multiple transaction notifications without retaining components or entity refs. */
    public void queue(Holder holder) {
        if (closed || queued.size() >= 2048) return;
        String key = sourceKey(holder);
        if (!queued.add(key)) return;
        World world = world(holder.worldName());
        if (world == null) { queued.remove(key); return; }
        try {
            world.execute(() -> {
                queued.remove(key);
                if (!closed) {
                    try { refresh(world, holder); }
                    catch (RuntimeException unavailable) { index.unload(holder); }
                }
            });
        } catch (RuntimeException ignored) { queued.remove(key); }
    }

    /** Verifies exactly one recorded holder, without loading chunks or scanning other players. */
    public CompletionStage<Optional<Sighting>> verify(CaptureKey capture) {
        Sighting known = index.find(capture).orElse(null);
        if (known == null || closed) return CompletableFuture.completedFuture(Optional.empty());
        World world = world(known.holder().worldName());
        if (world == null) {
            index.unload(known.holder());
            return CompletableFuture.completedFuture(index.find(capture));
        }
        CompletableFuture<Optional<Sighting>> result = new CompletableFuture<>();
        try {
            world.execute(() -> {
                if (closed) { result.complete(Optional.empty()); return; }
                try {
                    refresh(world, known.holder());
                    result.complete(index.find(capture));
                } catch (RuntimeException failure) {
                    index.unload(known.holder());
                    result.complete(index.find(capture));
                }
            });
        } catch (RuntimeException failure) { index.unload(known.holder()); result.complete(index.find(capture)); }
        return result.completeOnTimeout(Optional.of(new Sighting(known.capture(), known.holder(),
                known.observedAtMs(), false, known.itemId())), 3, TimeUnit.SECONDS);
    }

    private void refresh(World world, Holder holder) {
        if (holder.kind() == Kind.CONTAINER) {
            var chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock((int) holder.x(), (int) holder.z()));
            if (chunk == null) { index.unload(holder); return; }
            var ref = chunk.getBlockComponentEntity((int) holder.x(), (int) holder.y(), (int) holder.z());
            var block = ref == null || !ref.isValid() ? null : world.getChunkStore().getStore()
                    .getComponent(ref, ItemContainerBlock.getComponentType());
            if (block == null) index.observe(holder, List.of(), System.currentTimeMillis());
            else {
                var type = chunk.getBlockType((int) holder.x(), (int) holder.y(), (int) holder.z());
                Holder current = new Holder(holder.kind(), holder.worldName(), holder.id(),
                        type == null ? "" : type.getId(), holder.x(), holder.y(), holder.z());
                observeContainer(current, block.getItemContainer());
            }
            return;
        }
        var ref = world.getEntityRef(UUID.fromString(holder.id()));
        if (ref == null || !ref.isValid()) { index.unload(holder); return; }
        var store = world.getEntityStore().getStore();
        if (holder.kind() == Kind.PLAYER) observePlayer(store, ref, world.getName());
        else observeDrop(store, ref, world.getName());
    }

    public void observePlayer(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref, String worldName) {
        if (closed) return;
        Player player = accessor.getComponent(ref, Player.getComponentType());
        if (player == null || player.getUuid() == null) return;
        var transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        var position = transform == null ? null : transform.getPosition();
        Holder holder = new Holder(Kind.PLAYER, worldName, player.getUuid().toString(),
                player.getPlayerRef() == null ? player.getUuid().toString() : player.getPlayerRef().getUsername(), position == null ? 0 : position.x,
                position == null ? 0 : position.y, position == null ? 0 : position.z);
        Map<CaptureKey, String> captures = new HashMap<>();
        if (InventoryComponent.EVERYTHING == null) return;
        for (var type : InventoryComponent.EVERYTHING) {
            var inventory = accessor.getComponent(ref, type);
            if (inventory != null) collect(inventory.getInventory(), captures);
        }
        index.observe(holder, captures, System.currentTimeMillis());
    }

    public void observeDrop(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref, String worldName) {
        if (closed) return;
        var uuid = accessor.getComponent(ref, UUIDComponent.getComponentType());
        var item = accessor.getComponent(ref, ItemComponent.getComponentType());
        var transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        if (uuid == null || transform == null) return;
        var position = transform.getPosition();
        Holder holder = new Holder(Kind.DROPPED, worldName, uuid.getUuid().toString(), "",
                position.x, position.y, position.z);
        CaptureKey capture = item == null ? null : CapturedItemMetadata.read(item.getItemStack());
        index.observe(holder, capture == null ? Map.of() : Map.of(capture, item.getItemStack().getItemId()),
                System.currentTimeMillis());
    }

    public void observeContainer(Holder holder, @Nullable ItemContainer container) {
        if (closed || container == null) return;
        Map<CaptureKey, String> captures = new HashMap<>();
        collect(container, captures);
        index.observe(holder, captures, System.currentTimeMillis());
    }

    private static void collect(ItemContainer container, Map<CaptureKey, String> captures) {
        for (short slot = 0, capacity = container.getCapacity(); slot < capacity; slot++) {
            var stack = container.getItemStack(slot);
            CaptureKey capture = CapturedItemMetadata.read(stack);
            if (capture != null) captures.put(capture, stack.getItemId());
        }
    }

    public static Holder entityHolder(Kind kind, String world, UUID id) {
        return new Holder(kind, world, id.toString(), "", 0, 0, 0);
    }

    public static String sourceKey(Holder holder) { return holder.kind() + ":" + holder.worldName() + ":" + holder.id(); }

    @Nullable
    private static World world(String name) {
        Universe universe = Universe.get();
        return universe == null ? null : universe.getWorld(name);
    }

    @Override public void close() {
        closed = true;
        for (Runnable action : cleanup) action.run();
        cleanup.clear();
        if (saver != null) saver.shutdown();
        saveIfDirty();
        synchronized (this) { cache = null; }
        queued.clear();
        index.clear();
    }
}
