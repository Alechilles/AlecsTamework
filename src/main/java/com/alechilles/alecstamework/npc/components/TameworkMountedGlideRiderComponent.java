package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Rider-side marker linking a mounted player to an active Tamework mounted glide mount.
 */
public final class TameworkMountedGlideRiderComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkMountedGlideRiderComponent> CODEC = BuilderCodec.builder(
            TameworkMountedGlideRiderComponent.class,
            TameworkMountedGlideRiderComponent::new
    ).append(
            new KeyedCodec<>("MountUuid", Codec.STRING),
            TameworkMountedGlideRiderComponent::setMountUuid,
            TameworkMountedGlideRiderComponent::getMountUuid
    ).add().build();

    private String mountUuid = "";

    public TameworkMountedGlideRiderComponent() {
    }

    public TameworkMountedGlideRiderComponent(String mountUuid) {
        setMountUuid(mountUuid);
    }

    public static ComponentType<EntityStore, TameworkMountedGlideRiderComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getMountedGlideRiderComponentType() : null;
    }

    public String getMountUuid() {
        return mountUuid;
    }

    public void setMountUuid(String mountUuid) {
        this.mountUuid = sanitizeString(mountUuid);
    }

    @Override
    public TameworkMountedGlideRiderComponent clone() {
        return new TameworkMountedGlideRiderComponent(mountUuid);
    }

    private static String sanitizeString(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
