package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Resolves block-state locations across the Update 5 column and Update 6 section models.
 */
public final class HytaleBlockStateAccess {
    private static final String BLOCK_STATE_INFO_CLASS =
            "com.hypixel.hytale.server.core.modules.block.BlockModule$BlockStateInfo";
    private static final String CHUNK_SECTION_CLASS =
            "com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection";
    private static final Bindings BINDINGS = bind();
    private static final MethodHandle LEGACY_GET_BLOCK = bindLegacyGetBlock();
    private static final MethodHandle LEGACY_GET_ROTATION = bindLegacyGetRotation();
    private static final MethodHandle LEGACY_SET_BLOCK = bindLegacySetBlock();
    private static final MethodHandle LEGACY_SET_INTERACTION_STATE = bindLegacySetInteractionState();

    private HytaleBlockStateAccess() {
    }

    @Nullable
    public static BlockLocation resolve(@Nullable Store<ChunkStore> store,
                                        @Nullable Object blockStateInfo) {
        if (store == null || blockStateInfo == null) {
            return null;
        }
        try {
            return BINDINGS.sectionModel()
                    ? resolveSectionLocation(store, blockStateInfo)
                    : resolveColumnLocation(store, blockStateInfo);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static BlockType blockTypeAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        int blockId = blockIdAt(chunk, x, y, z);
        return blockId < 0 ? null : BlockType.getAssetMap().getAsset(blockId);
    }

    /** Returns -1 when the requested block section is unavailable. */
    public static int blockIdAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return -1;
        }
        if (HytaleApiLevel.isUpdate6OrLater()) {
            BlockSection section = blockSectionAt(chunk, x, y, z);
            return section == null ? -1 : section.get(x, y, z);
        }
        try {
            return (int) LEGACY_GET_BLOCK.invoke(chunk, x, y, z);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not read an Update 5 block", failure);
        }
    }

    public static int rotationAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return 0;
        }
        if (HytaleApiLevel.isUpdate6OrLater()) {
            BlockSection section = blockSectionAt(chunk, x, y, z);
            return section == null ? 0 : section.getRotationIndex(x, y, z);
        }
        try {
            return (int) LEGACY_GET_ROTATION.invoke(chunk, x, y, z);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not read an Update 5 block rotation", failure);
        }
    }

    public static boolean setBlock(@Nullable WorldChunk chunk, int x, int y, int z,
                                   int id, @Nonnull BlockType type, int rotation,
                                   int filler, int settings) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return false;
        }
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ChunkStore chunkStore = chunkStore(chunk);
            Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
            return sectionRef != null && BlockOperations.setBlock(
                    chunkStore, sectionRef, x, y, z, id, type, rotation, filler, settings);
        }
        try {
            return (boolean) LEGACY_SET_BLOCK.invoke(chunk, x, y, z, id, type,
                    rotation, filler, settings);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not write an Update 5 block", failure);
        }
    }

    public static void setInteractionState(@Nullable WorldChunk chunk, int x, int y, int z,
                                           @Nonnull BlockType type, @Nonnull String state) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return;
        }
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ChunkStore chunkStore = chunkStore(chunk);
            Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
            if (sectionRef != null) {
                BlockOperations.setBlockInteractionState(chunkStore, sectionRef,
                        x, y, z, type, state, false);
            }
            return;
        }
        try {
            LEGACY_SET_INTERACTION_STATE.invoke(chunk, x, y, z, type, state, false);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not set an Update 5 block interaction state", failure);
        }
    }

    private static boolean sameColumn(@Nonnull WorldChunk chunk, int x, int z) {
        return chunk.getIndex() == ChunkUtil.indexChunkFromBlock(x, z);
    }

    @Nullable
    private static ChunkStore chunkStore(@Nonnull WorldChunk chunk) {
        Ref<ChunkStore> ref = chunk.getReference();
        return ref != null && ref.isValid() ? ref.getStore().getExternalData() : null;
    }

    @Nullable
    private static Ref<ChunkStore> sectionRefAt(@Nullable ChunkStore chunkStore,
                                                int x, int y, int z) {
        if (chunkStore == null) {
            return null;
        }
        Ref<ChunkStore> ref = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        return ref != null && ref.isValid() ? ref : null;
    }

    @Nullable
    private static BlockSection blockSectionAt(@Nonnull WorldChunk chunk, int x, int y, int z) {
        ChunkStore chunkStore = chunkStore(chunk);
        Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
        return sectionRef == null ? null
                : chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
    }

    @Nullable
    private static MethodHandle bindLegacyGetBlock() {
        return bindLegacy("getBlock", int.class,
                int.class, int.class, int.class);
    }

    @Nullable
    private static MethodHandle bindLegacyGetRotation() {
        return bindLegacy("getRotationIndex", int.class,
                int.class, int.class, int.class);
    }

    @Nullable
    private static MethodHandle bindLegacySetBlock() {
        return bindLegacy("setBlock", boolean.class,
                int.class, int.class, int.class, int.class, BlockType.class,
                int.class, int.class, int.class);
    }

    @Nullable
    private static MethodHandle bindLegacySetInteractionState() {
        return bindLegacy("setBlockInteractionState", void.class,
                int.class, int.class, int.class, BlockType.class,
                String.class, boolean.class);
    }

    @Nullable
    private static MethodHandle bindLegacy(String name, Class<?> returnType, Class<?>... parameters) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(WorldChunk.class, name,
                    MethodType.methodType(returnType, parameters));
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Nullable
    private static BlockLocation resolveSectionLocation(@Nonnull Store<ChunkStore> store,
                                                        @Nonnull Object blockStateInfo) throws Throwable {
        Vector3i position = new Vector3i();
        boolean resolved = (boolean) BINDINGS.fillWorldPosition().invokeExact(
                blockStateInfo, (ComponentAccessor<ChunkStore>) store, position);
        Ref<ChunkStore> sectionRef = invokeReference(BINDINGS.getLocationReference(), blockStateInfo);
        if (!resolved || sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        ComponentType<ChunkStore, ?> sectionType = invokeComponentType(
                BINDINGS.getSectionComponentType());
        Component<ChunkStore> section = getComponent(store, sectionRef, sectionType);
        if (section == null) {
            return null;
        }
        Ref<ChunkStore> columnRef = invokeReference(BINDINGS.getColumnReference(), section);
        WorldChunk chunk = columnRef == null || !columnRef.isValid()
                ? null : store.getComponent(columnRef, WorldChunk.getComponentType());
        return chunk == null ? null : new BlockLocation(chunk, position.x, position.y, position.z);
    }

    @Nullable
    private static BlockLocation resolveColumnLocation(@Nonnull Store<ChunkStore> store,
                                                       @Nonnull Object blockStateInfo) throws Throwable {
        Ref<ChunkStore> columnRef = invokeReference(BINDINGS.getLocationReference(), blockStateInfo);
        if (columnRef == null || !columnRef.isValid()) {
            return null;
        }
        WorldChunk chunk = store.getComponent(columnRef, WorldChunk.getComponentType());
        if (chunk == null) {
            return null;
        }
        int index = (int) BINDINGS.getIndex().invokeExact(blockStateInfo);
        int localX = index & ChunkUtil.SIZE_MINUS_1;
        int y = (index >>> ChunkUtil.BITS2) & ChunkUtil.HEIGHT_MASK;
        int localZ = (index >>> ChunkUtil.BITS) & ChunkUtil.SIZE_MINUS_1;
        return new BlockLocation(
                chunk,
                ChunkUtil.worldCoordFromLocalCoord(chunk.getX(), localX),
                y,
                ChunkUtil.worldCoordFromLocalCoord(chunk.getZ(), localZ));
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Ref<ChunkStore> invokeReference(@Nonnull MethodHandle handle,
                                                   @Nonnull Object target) throws Throwable {
        return (Ref<ChunkStore>) (Ref<?>) handle.invokeExact(target);
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    private static ComponentType<ChunkStore, ?> invokeComponentType(
            @Nonnull MethodHandle handle) throws Throwable {
        return (ComponentType<ChunkStore, ?>) (ComponentType<?, ?>) handle.invokeExact();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    private static Component<ChunkStore> getComponent(@Nonnull Store<ChunkStore> store,
                                                      @Nonnull Ref<ChunkStore> ref,
                                                      @Nonnull ComponentType<ChunkStore, ?> type) {
        return store.getComponent(ref, (ComponentType) type);
    }

    @Nonnull
    private static Bindings bind() {
        try {
            Class<?> infoType = Class.forName(BLOCK_STATE_INFO_CLASS);
            try {
                return bindSectionModel(infoType);
            } catch (NoSuchMethodException ignored) {
                return bindColumnModel(infoType);
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nonnull
    private static Bindings bindSectionModel(@Nonnull Class<?> infoType)
            throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle fillWorldPosition = lookup.findVirtual(
                infoType,
                "fillWorldPos",
                MethodType.methodType(boolean.class, ComponentAccessor.class, Vector3i.class)
        ).asType(MethodType.methodType(
                boolean.class, Object.class, ComponentAccessor.class, Vector3i.class));
        MethodHandle getSectionReference = lookup.findVirtual(
                infoType, "getSectionRef", MethodType.methodType(Ref.class)
        ).asType(MethodType.methodType(Ref.class, Object.class));
        Class<?> sectionType = Class.forName(CHUNK_SECTION_CLASS);
        MethodHandle getColumnReference = lookup.findVirtual(
                sectionType, "getChunkColumnReference", MethodType.methodType(Ref.class)
        ).asType(MethodType.methodType(Ref.class, Object.class));
        MethodHandle getSectionComponentType = lookup.findStatic(
                sectionType, "getComponentType", MethodType.methodType(ComponentType.class));
        return new Bindings(
                true,
                fillWorldPosition,
                getSectionReference,
                null,
                getColumnReference,
                getSectionComponentType);
    }

    @Nonnull
    private static Bindings bindColumnModel(@Nonnull Class<?> infoType)
            throws NoSuchMethodException, IllegalAccessException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle getChunkReference = lookup.findVirtual(
                infoType, "getChunkRef", MethodType.methodType(Ref.class)
        ).asType(MethodType.methodType(Ref.class, Object.class));
        MethodHandle getIndex = lookup.findVirtual(
                infoType, "getIndex", MethodType.methodType(int.class)
        ).asType(MethodType.methodType(int.class, Object.class));
        return new Bindings(false, null, getChunkReference, getIndex, null, null);
    }

    /** A loaded block location and its owning chunk column. */
    public record BlockLocation(@Nonnull WorldChunk chunk, int x, int y, int z) {
    }

    private record Bindings(
            boolean sectionModel,
            @Nullable MethodHandle fillWorldPosition,
            @Nonnull MethodHandle getLocationReference,
            @Nullable MethodHandle getIndex,
            @Nullable MethodHandle getColumnReference,
            @Nullable MethodHandle getSectionComponentType) {
    }
}
