package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks a player who turned the Companion Inspect camera off. Its presence is the whole setting: players without it
 * get the camera. Read and write it on the player's world thread; engine player saving owns durability.
 */
public final class TameworkInspectCameraPreferenceComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkInspectCameraPreferenceComponent> CODEC = BuilderCodec
            .builder(TameworkInspectCameraPreferenceComponent.class, TameworkInspectCameraPreferenceComponent::new)
            .build();
    private static ComponentType<EntityStore, TameworkInspectCameraPreferenceComponent> type;

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TameworkInspectCameraPreferenceComponent.class,
                "TameworkInspectCameraOff", CODEC);
    }

    /** Whether the inspect camera is on for this player; true when unknown. Read only. */
    public static boolean enabled(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        return type == null || playerRef == null || !playerRef.isValid() || store == null
                || store.getComponent(playerRef, type) == null;
    }

    /** Turns the camera on or off for the player. Call from a page or command callback, never an ECS system. */
    public static void setEnabled(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store,
                                  boolean enabled) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return;
        }
        if (enabled) {
            store.tryRemoveComponent(playerRef, type);
        } else {
            store.putComponent(playerRef, type, new TameworkInspectCameraPreferenceComponent());
        }
    }

    @Override
    @Nonnull
    public TameworkInspectCameraPreferenceComponent clone() {
        return new TameworkInspectCameraPreferenceComponent();
    }
}
