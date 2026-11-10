package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A player's Companion Inspect choices: whether the inspect camera is off, and whether a command from the panel
 * closes it. Players who keep both defaults (camera on, panel stays open) have no component. Read and write it on the
 * player's world thread; engine player saving owns durability.
 */
public final class TameworkInspectPreferencesComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkInspectPreferencesComponent> CODEC = BuilderCodec
            .builder(TameworkInspectPreferencesComponent.class, TameworkInspectPreferencesComponent::new)
            .append(new KeyedCodec<>("CameraOff", Codec.BOOLEAN),
                    (c, v) -> c.cameraOff = Boolean.TRUE.equals(v), c -> c.cameraOff).add()
            .append(new KeyedCodec<>("CloseAfterCommand", Codec.BOOLEAN),
                    (c, v) -> c.closeAfterCommand = Boolean.TRUE.equals(v), c -> c.closeAfterCommand).add()
            .build();
    private static ComponentType<EntityStore, TameworkInspectPreferencesComponent> type;
    private boolean cameraOff;
    private boolean closeAfterCommand;

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TameworkInspectPreferencesComponent.class,
                "TameworkInspectPreferences", CODEC);
    }

    /** Whether the inspect camera is on for this player; true when unknown. Read only. */
    public static boolean cameraEnabled(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        TameworkInspectPreferencesComponent saved = read(playerRef, store);
        return saved == null || !saved.cameraOff;
    }

    /** Whether a command from Companion Inspect closes it for this player; false when unknown. Read only. */
    public static boolean closeAfterCommand(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        TameworkInspectPreferencesComponent saved = read(playerRef, store);
        return saved != null && saved.closeAfterCommand;
    }

    /** Turns the camera on or off. Call from a page or command callback, never an ECS system iteration. */
    public static void setCameraEnabled(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store,
                                        boolean enabled) {
        write(playerRef, store, !enabled, closeAfterCommand(playerRef, store));
    }

    /** Sets whether a command closes the panel. Same calling rules as {@link #setCameraEnabled}. */
    public static void setCloseAfterCommand(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store,
                                            boolean close) {
        write(playerRef, store, !cameraEnabled(playerRef, store), close);
    }

    @Nullable
    private static TameworkInspectPreferencesComponent read(@Nullable Ref<EntityStore> playerRef,
                                                            @Nullable Store<EntityStore> store) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        return store.getComponent(playerRef, type);
    }

    private static void write(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store,
                              boolean cameraOff, boolean closeAfterCommand) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return;
        }
        if (!cameraOff && !closeAfterCommand) {
            store.tryRemoveComponent(playerRef, type);
            return;
        }
        TameworkInspectPreferencesComponent next = new TameworkInspectPreferencesComponent();
        next.cameraOff = cameraOff;
        next.closeAfterCommand = closeAfterCommand;
        store.putComponent(playerRef, type, next);
    }

    @Override
    @Nonnull
    public TameworkInspectPreferencesComponent clone() {
        TameworkInspectPreferencesComponent result = new TameworkInspectPreferencesComponent();
        result.cameraOff = cameraOff;
        result.closeAfterCommand = closeAfterCommand;
        return result;
    }
}
