package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves and saves an entity's chunk across the Update 5 column and Update 6 section models.
 */
public final class HytaleChunkAccess {
    private static final MethodHandle LEGACY_GET_CHUNK_REF = bindLegacyGetChunkRef();
    private static final MethodHandle LEGACY_MARK_CHUNK_DIRTY = bindLegacyMarkChunkDirty();
    private static final MethodHandle LEGACY_TO_HOLDER = bindLegacyToHolder();

    private HytaleChunkAccess() {
    }

    @Nullable
    public static WorldChunk currentWorldChunk(@Nullable TransformComponent transform,
                                               @Nullable World world) {
        if (transform == null || world == null) {
            return null;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunks = chunkStore != null ? chunkStore.getStore() : null;
        if (chunks == null) {
            return null;
        }
        Ref<ChunkStore> columnRef = HytaleApiLevel.isUpdate6OrLater()
                ? resolveUpdate6ColumnRef(transform, chunks)
                : resolveUpdate5ColumnRef(transform);
        if (columnRef == null || !columnRef.isValid()) {
            return null;
        }
        WorldChunk chunk = chunks.getComponent(columnRef, WorldChunk.getComponentType());
        return chunk != null && chunk.getWorld() == world ? chunk : null;
    }

    /** Marks the current source entity section for persistence before an entity is detached. */
    public static void markNeedsSaving(@Nonnull TransformComponent transform,
                                       @Nonnull Store<EntityStore> entityStore) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            EntitySection section = resolveUpdate6EntitySection(transform, entityStore);
            if (section == null) {
                throw new IllegalStateException("Update 6 source entity section is not available");
            }
            section.markNeedsSaving();
            return;
        }
        if (LEGACY_MARK_CHUNK_DIRTY == null) {
            throw new IllegalStateException("Missing Update 5 TransformComponent.markChunkDirty accessor");
        }
        try {
            LEGACY_MARK_CHUNK_DIRTY.invoke(transform, entityStore);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not mark an Update 5 chunk dirty", throwable);
        }
    }

    /** Uses the Update 6 column snapshot helper and the Update 5 holder save contract. */
    @Nonnull
    public static CompletableFuture<Void> saveColumn(@Nonnull IChunkSaver saver,
                                                     @Nonnull WorldChunk chunk,
                                                     @Nonnull World world) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ChunkStore chunkStore = world.getChunkStore();
            Store<ChunkStore> chunks = chunkStore != null ? chunkStore.getStore() : null;
            Ref<ChunkStore> columnRef = chunk.getReference();
            if (chunks == null || columnRef == null || !columnRef.isValid()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Update 6 chunk column is not available"));
            }
            return saver.saveChunkColumn(
                    chunk.getX(),
                    chunk.getZ(),
                    chunks,
                    columnRef,
                    ForkJoinPool.commonPool(),
                    null);
        }
        return saver.saveHolder(chunk.getX(), chunk.getZ(), legacyHolder(chunk));
    }

    @Nullable
    private static Ref<ChunkStore> resolveUpdate6ColumnRef(@Nonnull TransformComponent transform,
                                                           @Nonnull Store<ChunkStore> chunks) {
        Ref<ChunkStore> sectionRef = transform.getSectionRef();
        if (sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        ChunkSection section = chunks.getComponent(sectionRef, ChunkSection.getComponentType());
        return section != null ? section.getChunkColumnReference() : null;
    }

    @Nullable
    private static EntitySection resolveUpdate6EntitySection(
            @Nonnull TransformComponent transform,
            @Nonnull Store<EntityStore> entityStore
    ) {
        EntityStore externalData = entityStore.getExternalData();
        World world = externalData != null ? externalData.getWorld() : null;
        ChunkStore chunkStore = world != null ? world.getChunkStore() : null;
        Store<ChunkStore> chunks = chunkStore != null ? chunkStore.getStore() : null;
        Ref<ChunkStore> sectionRef = transform.getSectionRef();
        if (chunks == null || sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        return chunks.getComponent(sectionRef, EntitySection.getComponentType());
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    private static Holder<ChunkStore> legacyHolder(@Nonnull WorldChunk chunk) {
        if (LEGACY_TO_HOLDER == null) {
            throw new IllegalStateException("Missing Update 5 WorldChunk.toHolder accessor");
        }
        try {
            return (Holder<ChunkStore>) LEGACY_TO_HOLDER.invoke(chunk);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not snapshot an Update 5 chunk holder", throwable);
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Ref<ChunkStore> resolveUpdate5ColumnRef(@Nonnull TransformComponent transform) {
        if (LEGACY_GET_CHUNK_REF == null) {
            throw new IllegalStateException("Missing Update 5 TransformComponent.getChunkRef accessor");
        }
        try {
            return (Ref<ChunkStore>) LEGACY_GET_CHUNK_REF.invoke(transform);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not resolve an Update 5 chunk reference", throwable);
        }
    }

    @Nullable
    private static MethodHandle bindLegacyGetChunkRef() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    TransformComponent.class,
                    "getChunkRef",
                    MethodType.methodType(Ref.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nullable
    private static MethodHandle bindLegacyMarkChunkDirty() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    TransformComponent.class,
                    "markChunkDirty",
                    MethodType.methodType(void.class, ComponentAccessor.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nullable
    private static MethodHandle bindLegacyToHolder() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    WorldChunk.class,
                    "toHolder",
                    MethodType.methodType(Holder.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
