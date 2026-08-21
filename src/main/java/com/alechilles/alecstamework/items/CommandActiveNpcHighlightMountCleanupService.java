package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Removes active-highlight helpers before Hytale changes an NPC's mount graph. */
public final class CommandActiveNpcHighlightMountCleanupService {
    @Nullable
    private final ComponentType<EntityStore, MountedComponent> mountedType;
    @Nullable
    private final ComponentType<EntityStore, ModelComponent> modelType;
    @Nullable
    private final Query<EntityStore> helperQuery;

    public CommandActiveNpcHighlightMountCleanupService() {
        this.mountedType = null;
        this.modelType = null;
        this.helperQuery = null;
    }

    CommandActiveNpcHighlightMountCleanupService(
            @Nonnull ComponentType<EntityStore, MountedComponent> mountedType,
            @Nonnull ComponentType<EntityStore, ModelComponent> modelType
    ) {
        this.mountedType = mountedType;
        this.modelType = modelType;
        this.helperQuery = Query.and(mountedType, modelType);
    }

    /** Removes only marker helpers attached to the NPC named by {@code npcRef}. */
    public void removeBeforeMount(@Nonnull Store<EntityStore> store,
                                  @Nonnull Ref<EntityStore> npcRef) {
        ComponentType<EntityStore, MountedComponent> resolvedMountedType = mountedType != null
                ? mountedType : MountedComponent.getComponentType();
        ComponentType<EntityStore, ModelComponent> resolvedModelType = modelType != null
                ? modelType : ModelComponent.getComponentType();
        if (resolvedMountedType == null || resolvedModelType == null) {
            return;
        }
        Query<EntityStore> resolvedQuery = helperQuery != null
                ? helperQuery : Query.and(resolvedMountedType, resolvedModelType);
        store.forEachChunk(
                resolvedQuery,
                (ArchetypeChunk<EntityStore> chunk,
                 CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        MountedComponent mounted = chunk.getComponent(
                                index, resolvedMountedType
                        );
                        ModelComponent model = chunk.getComponent(index, resolvedModelType);
                        if (isHighlightHelperFor(mounted, model, npcRef)) {
                            commandBuffer.removeEntity(
                                    chunk.getReferenceTo(index), RemoveReason.REMOVE
                            );
                        }
                    }
                }
        );
    }

    private static boolean isHighlightHelperFor(
            MountedComponent mounted,
            ModelComponent modelComponent,
            Ref<EntityStore> npcRef
    ) {
        if (mounted == null || modelComponent == null
                || !npcRef.equals(mounted.getMountedToEntity())) {
            return false;
        }
        Model model = modelComponent.getModel();
        return model != null && CommandActiveNpcHighlightProxyService.MODEL_ASSET_ID.equals(
                model.getModelAssetId()
        );
    }
}
