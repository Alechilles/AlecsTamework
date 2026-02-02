package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

public final class TameworkOwnerComponent implements Component<EntityStore> {
    public static final String OWNER_ID_TAG = "OwnerId";
    public static final String OWNER_NAME_TAG = "OwnerName";

    public static final BuilderCodec<TameworkOwnerComponent> CODEC = BuilderCodec.builder(
            TameworkOwnerComponent.class,
            TameworkOwnerComponent::new
    ).append(
            new KeyedCodec<>(OWNER_ID_TAG, new UUIDBinaryCodec()),
            TameworkOwnerComponent::setOwnerId,
            TameworkOwnerComponent::getOwnerId
    ).add().append(
            new KeyedCodec<>(OWNER_NAME_TAG, new StringCodec()),
            TameworkOwnerComponent::setOwnerName,
            TameworkOwnerComponent::getOwnerName
    ).add().build();

    private UUID ownerId;
    private String ownerName;

    public TameworkOwnerComponent() {
    }

    public TameworkOwnerComponent(UUID ownerId, String ownerName) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
    }

    public static ComponentType<EntityStore, TameworkOwnerComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getOwnerComponentType() : null;
    }

    public boolean hasOwner() {
        return ownerId != null;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    @Override
    public TameworkOwnerComponent clone() {
        return new TameworkOwnerComponent(ownerId, ownerName);
    }
}
