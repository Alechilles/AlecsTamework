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
    private boolean clientCameraApplied;
    private double clientSpeedModifier = -1.0;

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

    public boolean isClientCameraApplied() {
        return clientCameraApplied;
    }

    public void setClientCameraApplied(boolean clientCameraApplied) {
        this.clientCameraApplied = clientCameraApplied;
    }

    public double getClientSpeedModifier() {
        return clientSpeedModifier;
    }

    public void setClientSpeedModifier(double clientSpeedModifier) {
        this.clientSpeedModifier = Double.isFinite(clientSpeedModifier) && clientSpeedModifier > 0.0
                ? clientSpeedModifier
                : -1.0;
    }

    @Override
    public TameworkMountedGlideRiderComponent clone() {
        TameworkMountedGlideRiderComponent clone = new TameworkMountedGlideRiderComponent(mountUuid);
        clone.clientCameraApplied = clientCameraApplied;
        clone.clientSpeedModifier = clientSpeedModifier;
        return clone;
    }

    private static String sanitizeString(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
