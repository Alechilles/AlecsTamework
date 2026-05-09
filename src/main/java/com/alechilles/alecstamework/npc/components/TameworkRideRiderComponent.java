package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores rider-side linkage to the current Tamework ride mount.
 */
public final class TameworkRideRiderComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkRideRiderComponent> CODEC = BuilderCodec.builder(
            TameworkRideRiderComponent.class,
            TameworkRideRiderComponent::new
    ).append(
            new KeyedCodec<>("MountUuid", Codec.STRING),
            TameworkRideRiderComponent::setMountUuid,
            TameworkRideRiderComponent::getMountUuid
    ).add().build();

    private String mountUuid = "";
    private boolean clientCameraApplied;

    public TameworkRideRiderComponent() {
    }

    public TameworkRideRiderComponent(String mountUuid) {
        setMountUuid(mountUuid);
    }

    public static ComponentType<EntityStore, TameworkRideRiderComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getRideRiderComponentType() : null;
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

    @Override
    public TameworkRideRiderComponent clone() {
        TameworkRideRiderComponent clone = new TameworkRideRiderComponent(mountUuid);
        clone.clientCameraApplied = clientCameraApplied;
        return clone;
    }

    private static String sanitizeString(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
