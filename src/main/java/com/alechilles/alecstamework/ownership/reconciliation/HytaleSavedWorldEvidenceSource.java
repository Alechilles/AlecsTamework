package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Bounded scan of detached saved-chunk holders through Hytale 0.5.6 {@link IChunkLoader}.
 */
public final class HytaleSavedWorldEvidenceSource implements CompanionPopulationEvidenceSource {
    public enum Mode {
        WORLD_ENTITIES,
        BASE_CONTAINER_BLOCKS
    }

    private final String worldName;
    private final SavedChunkCatalog catalog;
    private final SavedChunkReader reader;
    private final long[] indexes;
    private final Descriptor descriptor;

    public HytaleSavedWorldEvidenceSource(
            @Nonnull World world,
            @Nonnull Mode mode,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull RecursiveItemContainerEvidenceScanner itemContainers
    ) throws IOException {
        this(world, mode, ownerType, itemContainers, Set.of(), "direct-source");
    }

    public HytaleSavedWorldEvidenceSource(
            @Nonnull World world,
            @Nonnull Mode mode,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull RecursiveItemContainerEvidenceScanner itemContainers,
            @Nonnull Set<UUID> knownNpcUuids,
            @Nonnull String mutableSourceEpoch
    ) throws IOException {
        this(runtimeAccess(world, mode, ownerType, itemContainers, knownNpcUuids), mode, mutableSourceEpoch);
    }

    private HytaleSavedWorldEvidenceSource(
            @Nonnull RuntimeAccess access,
            @Nonnull Mode mode,
            @Nonnull String mutableSourceEpoch
    ) throws IOException {
        this(access.worldName(), mode, access.catalog(), access.reader(), mutableSourceEpoch);
    }

    HytaleSavedWorldEvidenceSource(
            @Nonnull String worldName,
            @Nonnull Mode mode,
            @Nonnull SavedChunkCatalog catalog,
            @Nonnull SavedChunkReader reader,
            @Nonnull String mutableSourceEpoch
    ) throws IOException {
        this.worldName = requireText(worldName, "worldName");
        Mode requiredMode = Objects.requireNonNull(mode, "mode");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.indexes = snapshotIndexes(catalog);
        String key = coverageKey(this.worldName, requiredMode);
        this.descriptor = new Descriptor(
                key,
                dimension(requiredMode),
                this.worldName,
                ReconciliationGeneration.forStrings(
                        key,
                        List.of(
                                ReconciliationGeneration.forLongs(key, indexes),
                                requireText(mutableSourceEpoch, "mutableSourceEpoch")
                        )
                ),
                indexes.length
        );
    }

    @Nonnull
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Nonnull
    @Override
    public CompletableFuture<Batch> scan(long offset, int maxUnits) {
        int start = checkedStart(offset);
        int end = Math.min(indexes.length, start + requirePositive(maxUnits));
        CompletableFuture<List<CompanionPopulationEvidence>> future =
                CompletableFuture.completedFuture(new ArrayList<>());
        for (int index = start; index < end; index++) {
            long chunkIndex = indexes[index];
            int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
            int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
            future = future.thenCompose(evidence -> reader.read(chunkX, chunkZ).thenApply(found -> {
                evidence.addAll(Objects.requireNonNull(found, "saved chunk evidence"));
                return evidence;
            }));
        }
        return future.thenApply(evidence -> {
            boolean complete = end == indexes.length;
            if (complete && !sameIndexes()) {
                throw new IllegalStateException(
                        "Saved-world chunk catalog changed during reconciliation: " + worldName
                );
            }
            return new Batch(evidence, end, end - start, complete);
        });
    }

    private boolean sameIndexes() {
        try {
            return Arrays.equals(indexes, snapshotIndexes(catalog));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to verify saved-world chunk catalog.", exception);
        }
    }

    @Nonnull
    private static RuntimeAccess runtimeAccess(
            @Nonnull World world,
            @Nonnull Mode mode,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull RecursiveItemContainerEvidenceScanner itemContainers,
            @Nonnull Set<UUID> knownNpcUuids
    ) {
        World requiredWorld = Objects.requireNonNull(world, "world");
        Mode requiredMode = Objects.requireNonNull(mode, "mode");
        ComponentType<EntityStore, TameworkOwnerComponent> requiredOwnerType =
                Objects.requireNonNull(ownerType, "ownerType");
        RecursiveItemContainerEvidenceScanner requiredItemContainers =
                Objects.requireNonNull(itemContainers, "itemContainers");
        Set<UUID> requiredKnownNpcUuids = Set.copyOf(
                Objects.requireNonNull(knownNpcUuids, "knownNpcUuids")
        );
        String worldName = requireText(requiredWorld.getName(), "worldName");
        ChunkStore chunkStore = Objects.requireNonNull(requiredWorld.getChunkStore(), "chunkStore");
        IChunkLoader loader = Objects.requireNonNull(chunkStore.getLoader(), "chunkLoader");
        String coverageKey = coverageKey(worldName, requiredMode);
        return new RuntimeAccess(
                worldName,
                () -> loader.getIndexes().toLongArray(),
                (chunkX, chunkZ) -> loader.loadHolder(chunkX, chunkZ).thenApply(holder -> {
                    if (holder == null) {
                        throw new IllegalStateException(
                                "Saved chunk index returned no holder: "
                                        + worldName + " " + chunkX + "," + chunkZ
                        );
                    }
                    return scanHolder(
                            holder,
                            chunkX,
                            chunkZ,
                            requiredMode,
                            worldName,
                            coverageKey,
                            requiredOwnerType,
                            requiredItemContainers,
                            requiredKnownNpcUuids
                    );
                })
        );
    }

    @Nonnull
    private static long[] snapshotIndexes(@Nonnull SavedChunkCatalog catalog) throws IOException {
        long[] current = Objects.requireNonNull(catalog.getIndexes(), "saved chunk indexes").clone();
        Arrays.sort(current);
        return current;
    }

    @Nonnull
    private static List<CompanionPopulationEvidence> scanHolder(
            @Nonnull Holder<ChunkStore> holder,
            int chunkX,
            int chunkZ,
            @Nonnull Mode mode,
            @Nonnull String worldName,
            @Nonnull String coverageKey,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull RecursiveItemContainerEvidenceScanner itemContainers,
            @Nonnull Set<UUID> knownNpcUuids
    ) {
        return mode == Mode.WORLD_ENTITIES
                ? scanEntities(holder, chunkX, chunkZ, worldName, coverageKey, ownerType, knownNpcUuids)
                : scanBaseContainers(holder, chunkX, chunkZ, worldName, coverageKey, itemContainers);
    }

    @Nonnull
    private static List<CompanionPopulationEvidence> scanEntities(
            @Nonnull Holder<ChunkStore> holder,
            int chunkX,
            int chunkZ,
            @Nonnull String worldName,
            @Nonnull String coverageKey,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull Set<UUID> knownNpcUuids
    ) {
        ComponentType<ChunkStore, EntityChunk> entityChunkType = EntityChunk.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (entityChunkType == null || uuidType == null) {
            throw new IllegalStateException("Saved entity reconciliation component types are not registered.");
        }
        EntityChunk entityChunk = holder.getComponent(entityChunkType);
        if (entityChunk == null || entityChunk.getEntityHolders() == null) {
            return List.of();
        }
        List<CompanionPopulationEvidence> evidence = new ArrayList<>();
        for (Holder<EntityStore> entity : entityChunk.getEntityHolders()) {
            UUIDComponent identity = entity.getComponent(uuidType);
            TameworkOwnerComponent owner = entity.getComponent(ownerType);
            if (identity == null || identity.getUuid() == null) {
                if (owner == null) {
                    continue;
                }
                throw new IllegalStateException("Owned saved entity is missing UUIDComponent.");
            }
            UUID npcUuid = identity.getUuid();
            if (owner == null && !knownNpcUuids.contains(npcUuid)) {
                continue;
            }
            CompanionPopulationEvidence.Kind kind = entity.getComponent(
                    DeathComponent.getComponentType()
            ) == null
                    ? CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY
                    : CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY;
            evidence.add(new CompanionPopulationEvidence(
                    "world/" + worldName + "/chunk-" + chunkX + "," + chunkZ + "/entity-" + npcUuid,
                    npcUuid,
                    owner == null ? null : owner.getOwnerId(),
                    kind,
                    worldName,
                    worldName,
                    chunkX,
                    chunkZ,
                    coverageKey
            ));
        }
        return List.copyOf(evidence);
    }

    @Nonnull
    private static List<CompanionPopulationEvidence> scanBaseContainers(
            @Nonnull Holder<ChunkStore> holder,
            int chunkX,
            int chunkZ,
            @Nonnull String worldName,
            @Nonnull String coverageKey,
            @Nonnull RecursiveItemContainerEvidenceScanner itemContainers
    ) {
        ComponentType<ChunkStore, BlockComponentChunk> blocksType = BlockComponentChunk.getComponentType();
        ComponentType<ChunkStore, ItemContainerBlock> containerType = ItemContainerBlock.getComponentType();
        if (blocksType == null || containerType == null) {
            throw new IllegalStateException("Saved block-container reconciliation types are not registered.");
        }
        BlockComponentChunk blocks = holder.getComponent(blocksType);
        if (blocks == null || blocks.getEntityHolders() == null) {
            return List.of();
        }
        List<CompanionPopulationEvidence> evidence = new ArrayList<>();
        blocks.getEntityHolders().int2ObjectEntrySet().forEach(entry -> {
            ItemContainerBlock container = entry.getValue().getComponent(containerType);
            if (container == null || container.getItemContainer() == null) {
                return;
            }
            evidence.addAll(itemContainers.scan(
                    container.getItemContainer(),
                    "world/" + worldName + "/chunk-" + chunkX + "," + chunkZ
                            + "/block-container-" + entry.getIntKey(),
                    coverageKey
            ).evidence());
        });
        return List.copyOf(evidence);
    }

    private int checkedStart(long offset) {
        if (offset < 0L || offset > indexes.length) {
            throw new IllegalArgumentException("Saved-world cursor is outside the source snapshot.");
        }
        return Math.toIntExact(offset);
    }

    private static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxUnits must be positive.");
        }
        return value;
    }

    @Nonnull
    private static String coverageKey(@Nonnull String worldName, @Nonnull Mode mode) {
        return switch (mode) {
            case WORLD_ENTITIES -> "world-entities:" + worldName;
            case BASE_CONTAINER_BLOCKS -> "base-containers:" + worldName;
        };
    }

    @Nonnull
    private static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord.Dimension
    dimension(@Nonnull Mode mode) {
        return switch (mode) {
            case WORLD_ENTITIES -> com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES;
            case BASE_CONTAINER_BLOCKS -> com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS;
        };
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    @FunctionalInterface
    interface SavedChunkCatalog {
        long[] getIndexes() throws IOException;
    }

    @FunctionalInterface
    interface SavedChunkReader {
        CompletableFuture<List<CompanionPopulationEvidence>> read(int chunkX, int chunkZ);
    }

    private record RuntimeAccess(
            String worldName,
            SavedChunkCatalog catalog,
            SavedChunkReader reader
    ) {
    }
}
