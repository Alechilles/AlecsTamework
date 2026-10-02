package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.migrate.LegacyBodyLocate.SavedEntity;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Reads the entities a world has saved in its chunks, for the saved-chunk pass
 * ({@link LegacyBodyLocator}). The only engine-facing part of that pass; it decides nothing.
 *
 * <p>It asks the world's own {@link IChunkLoader}, the way the engine's storage migration does
 * ({@code IChunkStorageProvider.migrateFrom}): {@code loadHolder} reads and decodes a chunk on the
 * storage's threads and returns a detached holder. The chunk is not added to the world, nothing in
 * it is changed or saved, and no live entity, store or component is touched. Every method blocks
 * and must never be called on a world thread.</p>
 */
public final class SavedChunks implements LegacyBodyLocator.Chunks {
    private static final long LOAD_TIMEOUT_SECONDS = 15L;

    private final World world;

    public SavedChunks(@Nonnull World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Nonnull
    @Override
    public String world() {
        return world.getName();
    }

    @Nonnull
    @Override
    public long[] indexes() throws IOException {
        return loader().getIndexes().toLongArray();
    }

    @Nonnull
    @Override
    public List<SavedEntity> load(long index) throws Exception {
        Holder<ChunkStore> chunk = loader()
                .loadHolder(ChunkUtil.xOfChunkIndex(index), ChunkUtil.zOfChunkIndex(index))
                .get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return chunk == null ? List.of() : entities(chunk, world.getName());
    }

    @Override
    public boolean open() {
        return world.isAlive();
    }

    /**
     * The average tick length over the last ten seconds, as a share of the tick step, of the
     * busiest world that is loaded. The chunk decode runs on storage threads shared by the whole
     * server, so the busiest world is the better sign than only the world being read. The engine
     * keeps both numbers in plain fields a world thread writes, and its own metrics read them
     * (and the universe's concurrent world map) from other threads. A value read here can be a
     * little old, which is fine for choosing a pause. NaN when nothing could be read.
     */
    @Override
    public double tickLoad() {
        double busiest = Double.NaN;
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return tickLoad(world);
            }
            for (World loaded : universe.getWorlds().values()) {
                double load = tickLoad(loaded);
                if (!Double.isNaN(load) && !(load <= busiest)) {
                    busiest = load;
                }
            }
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
        return busiest;
    }

    private static double tickLoad(World world) {
        int step = world.getTickStepNanos();
        return step <= 0 || !world.isAlive() ? Double.NaN
                : world.getBufferedTickLengthMetricSet().getAverage(0) / step;
    }

    private IChunkLoader loader() throws IOException {
        IChunkLoader loader = world.getChunkStore().getLoader();
        if (loader == null) {
            throw new IOException("the chunk storage of world " + world.getName() + " is not ready");
        }
        return loader;
    }

    /**
     * Every saved entity of a decoded chunk that has a UUID and a position: the entities of each
     * section, and those of the chunk-level list older saves used.
     */
    @SuppressWarnings("deprecation")
    @Nonnull
    static List<SavedEntity> entities(@Nonnull Holder<ChunkStore> chunk, @Nonnull String worldName) {
        List<SavedEntity> entities = new ArrayList<>();
        ChunkColumn column = chunk.getComponent(ChunkColumn.getComponentType());
        Holder<ChunkStore>[] sections = column == null ? null : column.getSectionHolders();
        if (sections != null) {
            for (Holder<ChunkStore> section : sections) {
                EntitySection saved = section == null ? null : section.getComponent(EntitySection.getComponentType());
                if (saved != null) {
                    for (Holder<EntityStore> entity : saved.getEntityHolders()) {
                        add(entities, entity, worldName);
                    }
                }
            }
        }
        // This holder is ours alone and is dropped after this call, so taking the list changes nothing.
        EntityChunk legacy = chunk.getComponent(EntityChunk.getComponentType());
        Holder<EntityStore>[] legacyEntities = legacy == null ? null : legacy.takeEntityHolders();
        if (legacyEntities != null) {
            for (Holder<EntityStore> entity : legacyEntities) {
                add(entities, entity, worldName);
            }
        }
        return entities;
    }

    private static void add(List<SavedEntity> entities, @Nullable Holder<EntityStore> entity, String worldName) {
        if (entity == null) {
            return;
        }
        UUIDComponent identity = entity.getComponent(UUIDComponent.getComponentType());
        TransformComponent transform = entity.getComponent(TransformComponent.getComponentType());
        UUID uuid = identity == null ? null : identity.getUuid();
        if (uuid == null || transform == null) {
            return;
        }
        Vector3d position = transform.getPosition();
        entities.add(new SavedEntity(uuid, worldName, position.x, position.y, position.z,
                () -> EntityStore.REGISTRY.serialize(entity)));
    }
}
