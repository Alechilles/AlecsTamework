package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.ImportContext;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.Status;
import com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.SweepResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Owning-thread import seam that re-resolves an exact live managed coop before each step.
 *
 * <p>The behavior retains only services. The live block component is consumed synchronously by
 * {@link ManagedCoopVanillaImportService}; it is never stored in the context or captured by a
 * persistence continuation.</p>
 */
public final class ManagedCoopVanillaImportBehavior {
    private final ImportGateway imports;
    private final LiveContextResolver liveContexts;

    public ManagedCoopVanillaImportBehavior(
            @Nonnull ManagedCoopVanillaImportService imports) {
        this(imports::sweep, new HytaleLiveContextResolver(new ManagedCoopAuthorityResolver()));
    }

    ManagedCoopVanillaImportBehavior(@Nonnull ImportGateway imports,
                                     @Nonnull LiveContextResolver liveContexts) {
        this.imports = Objects.requireNonNull(imports, "imports");
        this.liveContexts = Objects.requireNonNull(liveContexts, "liveContexts");
    }

    /**
     * Must run before lifecycle recovery/planning for the supplied reliable scan context.
     */
    @Nonnull
    public SweepResult sweep(@Nonnull Store<ChunkStore> chunkStore,
                             @Nonnull World world,
                             @Nonnull ManagedCoopContext scannedContext,
                             long nowMs) {
        Objects.requireNonNull(chunkStore, "chunkStore");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(scannedContext, "scannedContext");
        final ResolvedLiveCoop live;
        try {
            live = liveContexts.resolve(chunkStore, world, scannedContext);
        } catch (RuntimeException | LinkageError exception) {
            return blocked("managed_coop_import_live_resolution_failed:" + detail(exception));
        }
        if (live == null) {
            return blocked("managed_coop_import_exact_live_context_unavailable");
        }
        return imports.sweep(
                new ImportContext(
                        scannedContext.authorityKey(),
                        scannedContext.coopId(),
                        live.maximumResidents(),
                        true
                ),
                live.coopBlock(),
                nowMs
        );
    }

    private SweepResult blocked(String detail) {
        return new SweepResult(Status.BLOCKED, detail, true);
    }

    private static String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface ImportGateway {
        SweepResult sweep(@Nonnull ImportContext context,
                          @Nonnull CoopBlock coop,
                          long nowMs);
    }

    @FunctionalInterface
    interface LiveContextResolver {
        @Nullable
        ResolvedLiveCoop resolve(@Nonnull Store<ChunkStore> chunkStore,
                                 @Nonnull World world,
                                 @Nonnull ManagedCoopContext context);
    }

    record ResolvedLiveCoop(@Nonnull CoopBlock coopBlock, int maximumResidents) {
        ResolvedLiveCoop {
            Objects.requireNonNull(coopBlock, "coopBlock");
            if (maximumResidents < 1) {
                throw new IllegalArgumentException("maximumResidents must be positive");
            }
        }
    }

    private static final class HytaleLiveContextResolver implements LiveContextResolver {
        private final ManagedCoopAuthorityResolver authorityResolver;

        private HytaleLiveContextResolver(ManagedCoopAuthorityResolver authorityResolver) {
            this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
        }

        @Nullable
        @Override
        public ResolvedLiveCoop resolve(Store<ChunkStore> store,
                                        World world,
                                        ManagedCoopContext expected) {
            if (!worldMatches(world, expected) || world.getChunkStore() == null
                    || world.getChunkStore().getStore() != store) {
                return null;
            }
            store.assertThread();
            ManagedCoopAuthorityKey key = expected.authorityKey();
            WorldChunk chunk = world.getChunkIfInMemory(
                    ChunkUtil.indexChunkFromBlock(key.x(), key.z()));
            if (chunk == null || chunk.getWorld() != world) {
                return null;
            }
            Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(key.x(), key.y(), key.z());
            ComponentType<ChunkStore, CoopBlock> coopType = CoopBlock.getComponentType();
            ComponentType<ChunkStore, BlockStateInfo> infoType = BlockStateInfo.getComponentType();
            ComponentType<ChunkStore, ItemContainerBlock> containerType =
                    ItemContainerBlock.getComponentType();
            if (blockRef == null || !blockRef.isValid() || coopType == null
                    || infoType == null || containerType == null) {
                return null;
            }
            CoopBlock coop = store.getComponent(blockRef, coopType);
            BlockStateInfo info = store.getComponent(blockRef, infoType);
            ItemContainerBlock containerBlock = store.getComponent(blockRef, containerType);
            BlockType blockType = chunk.getBlockType(key.x(), key.y(), key.z());
            FarmingCoopAsset coopAsset = coop == null ? null : coop.getCoopAsset();
            ItemContainer container = containerBlock == null
                    ? null : containerBlock.getItemContainer();
            if (coop == null || coopAsset == null
                    || blockType == null || !matchesBlockInfo(store, chunk, info, key)) {
                return null;
            }
            ManagedCoopContext current = authorityResolver.resolve(
                    key.worldName(), blockType.getId(), coopAsset.getId(),
                    new Vector3i(key.x(), key.y(), key.z()),
                    chunk.getRotationIndex(key.x(), key.y(), key.z()), container);
            if (current == null || !current.config().isManagedAuthorityEnabled()
                    || !current.matchesExact(
                    key.worldName(), expected.coopId(), key.x(), key.y(), key.z())
                    || current.blockRotationIndex() != expected.blockRotationIndex()
                    || !sameConfig(current, expected)) {
                return null;
            }
            return new ResolvedLiveCoop(
                    coop, current.config().getLifecycleRules().getMaxResidents());
        }

        private boolean worldMatches(World world, ManagedCoopContext context) {
            return world.getName() != null
                    && world.getName().equalsIgnoreCase(context.worldName());
        }

        private boolean sameConfig(ManagedCoopContext current, ManagedCoopContext expected) {
            String currentId = current.config().getId();
            String expectedId = expected.config().getId();
            if (currentId != null && !currentId.isBlank()
                    && expectedId != null && !expectedId.isBlank()) {
                return currentId.equalsIgnoreCase(expectedId);
            }
            return current.config() == expected.config();
        }

        private boolean matchesBlockInfo(Store<ChunkStore> store,
                                         WorldChunk expectedChunk,
                                         @Nullable BlockStateInfo info,
                                         ManagedCoopAuthorityKey key) {
            Ref<ChunkStore> chunkRef = info == null ? null : info.getChunkRef();
            if (chunkRef == null || !chunkRef.isValid()) {
                return false;
            }
            WorldChunk stateChunk = store.getComponent(chunkRef, WorldChunk.getComponentType());
            if (stateChunk == null || stateChunk.getWorld() != expectedChunk.getWorld()) {
                return false;
            }
            int blockIndex = info.getIndex();
            int x = ChunkUtil.worldCoordFromLocalCoord(
                    stateChunk.getX(), ChunkUtil.xFromBlockInColumn(blockIndex));
            int y = ChunkUtil.yFromBlockInColumn(blockIndex);
            int z = ChunkUtil.worldCoordFromLocalCoord(
                    stateChunk.getZ(), ChunkUtil.zFromBlockInColumn(blockIndex));
            return x == key.x() && y == key.y() && z == key.z();
        }
    }
}
