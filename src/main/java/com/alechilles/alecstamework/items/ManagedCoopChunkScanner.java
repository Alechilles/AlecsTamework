package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Discovers configured physical coops without delegating occupancy to vanilla state.
 *
 * <p>The Hytale backend reads vanilla block components only as location/config evidence. The
 * resulting contexts are resolved through {@link ManagedCoopAuthorityResolver}; resident lists,
 * admission methods, and vanilla mutation APIs are deliberately outside this class.</p>
 */
public final class ManagedCoopChunkScanner {
    public enum ScanStatus {
        RELIABLE,
        UNAVAILABLE,
        FAILED
    }

    /** Immutable discovery evidence copied from one block-component row. */
    public record CoopEvidence(@Nullable String worldName,
                               @Nullable String blockTypeId,
                               @Nullable String coopAssetId,
                               @Nullable Vector3i block,
                               int blockRotationIndex,
                               @Nullable ItemContainer container) {
        public CoopEvidence {
            block = block == null ? null : new Vector3i(block);
        }

        @Override
        public Vector3i block() {
            return block == null ? null : new Vector3i(block);
        }
    }

    public record EvidenceRead(@Nonnull ScanStatus status,
                               @Nonnull List<CoopEvidence> evidence,
                               @Nullable String detail) {
        public EvidenceRead {
            Objects.requireNonNull(status, "status");
            evidence = List.copyOf(evidence);
        }

        @Nonnull
        public static EvidenceRead reliable(@Nonnull List<CoopEvidence> evidence) {
            return new EvidenceRead(ScanStatus.RELIABLE, evidence, null);
        }
    }

    /** One exact, de-duplicated scan result used by a single runtime sweep. */
    public record ScanResult(@Nonnull ScanStatus status,
                             @Nonnull List<ManagedCoopContext> contexts,
                             int rejectedEvidence,
                             int duplicateEvidence,
                             @Nullable String detail) {
        public ScanResult {
            Objects.requireNonNull(status, "status");
            contexts = List.copyOf(contexts);
        }

        public boolean reliable() {
            return status == ScanStatus.RELIABLE;
        }
    }

    private final ManagedCoopAuthorityResolver authorityResolver;
    private final EvidenceSource evidenceSource;

    public ManagedCoopChunkScanner() {
        this(new ManagedCoopAuthorityResolver(), new HytaleEvidenceSource());
    }

    ManagedCoopChunkScanner(@Nonnull ManagedCoopAuthorityResolver authorityResolver,
                            @Nonnull EvidenceSource evidenceSource) {
        this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
        this.evidenceSource = Objects.requireNonNull(evidenceSource, "evidenceSource");
    }

    /** Reads one synchronous chunk-store snapshot and resolves only authority-eligible contexts. */
    @Nonnull
    public ScanResult scan(@Nonnull Store<ChunkStore> chunkStore, @Nonnull World world) {
        Objects.requireNonNull(chunkStore, "chunkStore");
        Objects.requireNonNull(world, "world");
        final EvidenceRead read;
        try {
            chunkStore.assertThread();
            read = evidenceSource.read(chunkStore, world);
        } catch (RuntimeException exception) {
            return new ScanResult(
                    ScanStatus.FAILED, List.of(), 0, 0,
                    failureDetail("managed_coop_chunk_scan", exception));
        }
        return resolve(read);
    }

    @Nonnull
    ScanResult resolve(@Nullable EvidenceRead read) {
        if (read == null || read.status() != ScanStatus.RELIABLE) {
            return new ScanResult(
                    read == null ? ScanStatus.FAILED : read.status(),
                    List.of(), 0, 0,
                    read == null ? "managed_coop_chunk_scan_result_missing" : read.detail());
        }

        Map<ManagedCoopAuthorityKey, ManagedCoopContext> byAuthority = new LinkedHashMap<>();
        int rejected = 0;
        int duplicates = 0;
        for (CoopEvidence evidence : read.evidence()) {
            if (evidence == null) {
                rejected++;
                continue;
            }
            ManagedCoopContext context = authorityResolver.resolve(
                    evidence.worldName(),
                    evidence.blockTypeId(),
                    evidence.coopAssetId(),
                    evidence.block(),
                    evidence.blockRotationIndex(),
                    evidence.container());
            if (context == null) {
                rejected++;
                continue;
            }
            ManagedCoopContext existing = byAuthority.putIfAbsent(context.authorityKey(), context);
            if (existing != null) {
                duplicates++;
                if (!existing.coopId().equals(context.coopId())) {
                    return new ScanResult(
                            ScanStatus.FAILED, List.of(), rejected, duplicates,
                            "managed_coop_scan_authority_config_conflict");
                }
            }
        }
        return new ScanResult(
                ScanStatus.RELIABLE,
                List.copyOf(byAuthority.values()),
                rejected,
                duplicates,
                null);
    }

    interface EvidenceSource {
        @Nonnull
        EvidenceRead read(@Nonnull Store<ChunkStore> chunkStore, @Nonnull World world);
    }

    /** Version-isolated Hytale component reader. */
    private static final class HytaleEvidenceSource implements EvidenceSource {
        @Nonnull
        @Override
        public EvidenceRead read(Store<ChunkStore> chunkStore, World world) {
            ComponentType<ChunkStore, ItemContainerBlock> itemContainerType =
                    ItemContainerBlock.getComponentType();
            ComponentType<ChunkStore, CoopBlock> coopType = CoopBlock.getComponentType();
            ComponentType<ChunkStore, BlockStateInfo> infoType =
                    BlockStateInfo.getComponentType();
            if (infoType == null || itemContainerType == null && coopType == null) {
                return new EvidenceRead(
                        ScanStatus.UNAVAILABLE, List.of(),
                        "managed_coop_scan_component_type_unavailable");
            }
            ArrayList<CoopEvidence> evidence = new ArrayList<>();
            if (itemContainerType != null) {
                scanType(chunkStore, world, itemContainerType, infoType, evidence);
            }
            if (coopType != null) {
                scanType(chunkStore, world, coopType, infoType, evidence);
            }
            return EvidenceRead.reliable(evidence);
        }

        private void scanType(Store<ChunkStore> store,
                              World world,
                              ComponentType<ChunkStore, ?> stateType,
                              ComponentType<ChunkStore, BlockStateInfo> blockInfoType,
                              List<CoopEvidence> target) {
            store.forEachChunk(
                    Query.and(castQueryType(stateType), castQueryType(blockInfoType)),
                    (ArchetypeChunk<ChunkStore> chunk, CommandBuffer<ChunkStore> ignored) ->
                            collectChunk(store, world, chunk, stateType, blockInfoType, target));
        }

        private void collectChunk(Store<ChunkStore> store,
                                  World world,
                                  ArchetypeChunk<ChunkStore> chunk,
                                  ComponentType<ChunkStore, ?> stateType,
                                  ComponentType<ChunkStore, BlockStateInfo> blockInfoType,
                                  List<CoopEvidence> target) {
            for (int index = 0; index < chunk.size(); index++) {
                Ref<ChunkStore> reference = chunk.getReferenceTo(index);
                if (reference == null || !reference.isValid()) {
                    continue;
                }
                Object state = chunk.getComponent(index, castType(stateType));
                BlockStateInfo info = chunk.getComponent(index, blockInfoType);
                CoopLocation location = location(store, info);
                if (state == null || location == null) {
                    continue;
                }
                target.add(new CoopEvidence(
                        world.getName(),
                        location.blockTypeId(),
                        coopAssetId(state),
                        location.block(),
                        location.rotationIndex(),
                        itemContainer(state)));
            }
        }

        @Nullable
        private CoopLocation location(Store<ChunkStore> store, @Nullable BlockStateInfo info) {
            Ref<ChunkStore> chunkRef = info != null ? info.getChunkRef() : null;
            if (chunkRef == null || !chunkRef.isValid()) {
                return null;
            }
            WorldChunk worldChunk = store.getComponent(chunkRef, WorldChunk.getComponentType());
            if (worldChunk == null) {
                return null;
            }
            int blockIndex = info.getIndex();
            int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
            int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
            int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
            int worldX = ChunkUtil.worldCoordFromLocalCoord(worldChunk.getX(), localX);
            int worldZ = ChunkUtil.worldCoordFromLocalCoord(worldChunk.getZ(), localZ);
            BlockType blockType = worldChunk.getBlockType(worldX, localY, worldZ);
            return new CoopLocation(
                    new Vector3i(worldX, localY, worldZ),
                    blockType != null ? blockType.getId() : null,
                    worldChunk.getRotationIndex(worldX, localY, worldZ));
        }

        @Nullable
        private String coopAssetId(Object state) {
            FarmingCoopAsset asset = state instanceof CoopBlock coop
                    ? coop.getCoopAsset() : null;
            return asset != null ? asset.getId() : null;
        }

        @Nullable
        private ItemContainer itemContainer(Object state) {
            return state instanceof ItemContainerBlock containerBlock
                    ? containerBlock.getItemContainer() : null;
        }

        @SuppressWarnings("unchecked")
        private static <T extends Component<ChunkStore>> ComponentType<ChunkStore, T> castType(
                ComponentType<ChunkStore, ?> type) {
            return (ComponentType<ChunkStore, T>) type;
        }

        @SuppressWarnings("unchecked")
        private static ComponentType<ChunkStore, ? extends Component<ChunkStore>> castQueryType(
                ComponentType<ChunkStore, ?> type) {
            return (ComponentType<ChunkStore, ? extends Component<ChunkStore>>) type;
        }

        private record CoopLocation(Vector3i block, @Nullable String blockTypeId, int rotationIndex) {
        }
    }

    private static String failureDetail(String stage, RuntimeException exception) {
        String message = exception.getMessage();
        return stage + (message == null || message.isBlank()
                ? ":" + exception.getClass().getSimpleName()
                : ":" + message);
    }
}
