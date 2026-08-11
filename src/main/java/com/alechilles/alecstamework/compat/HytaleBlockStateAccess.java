package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
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
