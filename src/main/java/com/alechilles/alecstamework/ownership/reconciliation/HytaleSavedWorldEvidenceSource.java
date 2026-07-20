package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
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
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
            future = future.thenCompose(evidence -> readChunk(chunkX, chunkZ).thenApply(found -> {
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

    @Nonnull
    private CompletableFuture<List<CompanionPopulationEvidence>> readChunk(
            int chunkX, int chunkZ) {
        CompletableFuture<List<CompanionPopulationEvidence>> loaded;
        try {
            loaded = Objects.requireNonNull(
                    reader.read(chunkX, chunkZ), "saved chunk read future");
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(chunkFailure(chunkX, chunkZ, throwable));
        }
        return loaded.handle((evidence, failure) -> {
            if (failure != null) {
                throw new CompletionException(chunkFailure(chunkX, chunkZ, failure));
            }
            return Objects.requireNonNull(evidence, "saved chunk evidence");
        });
    }

    private IllegalStateException chunkFailure(int chunkX, int chunkZ, Throwable throwable) {
        return new IllegalStateException(
                "Saved-world evidence scan failed: source=" + descriptor.coverageKey()
                        + " world=" + worldName + " chunk=" + chunkX + "," + chunkZ,
                Objects.requireNonNull(throwable, "throwable"));
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
        SavedEntityComponentTypes entityTypes = requiredMode == Mode.WORLD_ENTITIES
                ? savedEntityComponentTypes() : null;
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
                            requiredKnownNpcUuids,
                            entityTypes
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
            @Nonnull Set<UUID> knownNpcUuids,
            @Nullable SavedEntityComponentTypes entityTypes
    ) {
        return mode == Mode.WORLD_ENTITIES
                ? scanEntities(
                        holder, chunkX, chunkZ, worldName, coverageKey,
                        ownerType, knownNpcUuids,
                        Objects.requireNonNull(entityTypes, "saved entity component types")
                )
                : scanBaseContainers(holder, chunkX, chunkZ, worldName, coverageKey, itemContainers);
    }

    @Nonnull
    static List<CompanionPopulationEvidence> scanEntities(
            @Nonnull Holder<ChunkStore> holder,
            int chunkX,
            int chunkZ,
            @Nonnull String worldName,
            @Nonnull String coverageKey,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull Set<UUID> knownNpcUuids,
            @Nonnull SavedEntityComponentTypes types
    ) {
        EntityChunk entityChunk = holder.getComponent(types.entityChunkType());
        if (entityChunk == null || entityChunk.getEntityHolders() == null) {
            return List.of();
        }
        List<CompanionPopulationEvidence> evidence = new ArrayList<>();
        List<Holder<EntityStore>> entities = entityChunk.getEntityHolders();
        for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
            Holder<EntityStore> entity = entities.get(entityIndex);
            UUIDComponent identity = entity.getComponent(types.uuidType());
            UUID componentUuid = identity == null ? null : identity.getUuid();
            TameworkOwnerComponent owner = entity.getComponent(ownerType);
            NPCEntity npc = entity.getComponent(types.npcType());
            UUID legacyNpcUuid = npc == null ? null : npc.getUuid();
            UUID savedNpcUuid = savedNpcUuid(componentUuid, legacyNpcUuid);
            TameworkProjectionIdentityComponent marker = entity.getComponent(types.projectionType());
            boolean deathObserved = entity.getComponent(types.deathType()) != null;
            if (marker != null) {
                evidence.add(projectionEvidence(
                        marker, componentUuid, legacyNpcUuid, owner,
                        worldName, coverageKey, chunkX, chunkZ, entityIndex,
                        deathObserved
                ));
            }
            if (savedNpcUuid == null) {
                if (owner == null) {
                    continue;
                }
                throw new IllegalStateException("Owned saved entity has no persisted UUID.");
            }
            if (owner == null && !knownNpcUuids.contains(savedNpcUuid)) {
                continue;
            }
            CompanionPopulationEvidence.Kind kind = entityKind(deathObserved);
            evidence.add(new CompanionPopulationEvidence(
                    "world/" + worldName + "/chunk-" + chunkX + "," + chunkZ
                            + "/entity-" + savedNpcUuid,
                    savedNpcUuid,
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

    /** Mirrors Hytale's LegacyUUIDSystem migration while detached holders have not run systems. */
    @Nullable
    static UUID savedNpcUuid(@Nullable UUID componentUuid, @Nullable UUID legacyNpcUuid) {
        // LegacyUUIDSystem copies the component UUID back onto the legacy entity when both exist.
        // Detached holders have not run that migration yet, so an older legacy value is harmless.
        return componentUuid != null ? componentUuid : legacyNpcUuid;
    }

    @Nonnull
    static CompanionPopulationEvidence projectionEvidence(
            @Nonnull TameworkProjectionIdentityComponent marker,
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid,
            @Nullable TameworkOwnerComponent owner,
            @Nonnull String worldName,
            @Nonnull String coverageKey,
            int chunkX,
            int chunkZ,
            int entityIndex
    ) {
        return projectionEvidence(
                marker, componentUuid, legacyNpcUuid, owner,
                worldName, coverageKey, chunkX, chunkZ, entityIndex, false
        );
    }

    @Nonnull
    static CompanionPopulationEvidence projectionEvidence(
            @Nonnull TameworkProjectionIdentityComponent marker,
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid,
            @Nullable TameworkOwnerComponent owner,
            @Nonnull String worldName,
            @Nonnull String coverageKey,
            int chunkX,
            int chunkZ,
            int entityIndex,
            boolean deathObserved
    ) {
        // Generation zero is the durable legacy-migration value and is also accepted by the
        // loaded identity index. Retain it in the fingerprint so restart evidence stays exact.
        if (marker.getGeneration() < 0L) {
            throw new IllegalStateException(
                    "Saved projection marker generation must not be negative."
            );
        }
        String fingerprint = CompanionProjectionEvidence.fingerprint(
                marker.getProfileId(), marker.getOperationId(), marker.getProjectionKind(),
                marker.getSlotKey(), marker.getSourceNpcUuid(), marker.getGeneration()
        );
        String baseKey = "world/" + worldName + "/chunk-" + chunkX + "," + chunkZ
                + "/projection-marker-" + entityIndex;
        return new CompanionPopulationEvidence(
                CompanionProjectionEvidence.appendToEvidenceKey(
                        baseKey, fingerprint, componentUuid, legacyNpcUuid, deathObserved
                ),
                projectionEvidenceUuid(componentUuid, legacyNpcUuid, fingerprint),
                owner == null ? null : owner.getOwnerId(),
                true,
                CompanionPopulationEvidence.Kind.PROJECTION_MARKER,
                worldName,
                worldName,
                chunkX,
                chunkZ,
                coverageKey
        );
    }

    @Nonnull
    private static UUID projectionEvidenceUuid(
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid,
            @Nonnull String fingerprint
    ) {
        if (componentUuid != null) {
            return componentUuid;
        }
        if (legacyNpcUuid != null) {
            return legacyNpcUuid;
        }
        return UUID.fromString(
                fingerprint.substring(0, 8) + "-" + fingerprint.substring(8, 12)
                        + "-" + fingerprint.substring(12, 16)
                        + "-" + fingerprint.substring(16, 20)
                        + "-" + fingerprint.substring(20, 32)
        );
    }

    @Nonnull
    private static SavedEntityComponentTypes savedEntityComponentTypes() {
        ComponentType<ChunkStore, EntityChunk> entityChunkType = EntityChunk.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        ComponentType<EntityStore, DeathComponent> deathType = DeathComponent.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType =
                TameworkProjectionIdentityComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        requireAuthoritativeEntityTypes(
                entityChunkType, uuidType, deathType, projectionType, npcType
        );
        return new SavedEntityComponentTypes(
                entityChunkType, uuidType, deathType, projectionType, npcType
        );
    }

    static void requireAuthoritativeEntityTypes(
            @Nullable ComponentType<ChunkStore, EntityChunk> entityChunkType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, DeathComponent> deathType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType
    ) {
        if (entityChunkType == null || uuidType == null || deathType == null
                || projectionType == null || npcType == null) {
            throw new IllegalStateException(
                    "Saved entity reconciliation component, projection-marker, and NPC types"
                            + " must all be registered."
            );
        }
    }

    @Nonnull
    static CompanionPopulationEvidence.Kind entityKind(boolean deathComponentPresent) {
        return deathComponentPresent
                ? CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY
                : CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY;
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

    record SavedEntityComponentTypes(
            ComponentType<ChunkStore, EntityChunk> entityChunkType,
            ComponentType<EntityStore, UUIDComponent> uuidType,
            ComponentType<EntityStore, DeathComponent> deathType,
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            ComponentType<EntityStore, NPCEntity> npcType
    ) {
    }
}
