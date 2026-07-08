package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks the visual-only rider entity attached to a transformed avatar-flight player.
 */
public final class AvatarFlightRiderVisualComponent implements Component<EntityStore> {
    public static final BuilderCodec<AvatarFlightRiderVisualComponent> CODEC = BuilderCodec.builder(
            AvatarFlightRiderVisualComponent.class,
            AvatarFlightRiderVisualComponent::new
    )
            .<String>append(new KeyedCodec<>("OwnerUuid", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setOwnerUuid,
                    AvatarFlightRiderVisualComponent::getOwnerUuid)
            .add()
            .<String>append(new KeyedCodec<>("RiderEntityUuid", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setRiderEntityUuid,
                    AvatarFlightRiderVisualComponent::getRiderEntityUuid)
            .add()
            .<Boolean>append(new KeyedCodec<>("RiderEntity", Codec.BOOLEAN),
                    AvatarFlightRiderVisualComponent::setRiderEntity,
                    AvatarFlightRiderVisualComponent::isRiderEntity)
            .add()
            .<String>append(new KeyedCodec<>("EquipmentSignature", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setEquipmentSignature,
                    AvatarFlightRiderVisualComponent::getEquipmentSignature)
            .add()
            .<String>append(new KeyedCodec<>("HiddenOwnerEquipmentSignature", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setHiddenOwnerEquipmentSignature,
                    AvatarFlightRiderVisualComponent::getHiddenOwnerEquipmentSignature)
            .add()
            .<String>append(new KeyedCodec<>("HiddenOwnerSourceEquipmentSignature", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setHiddenOwnerSourceEquipmentSignature,
                    AvatarFlightRiderVisualComponent::getHiddenOwnerSourceEquipmentSignature)
            .add()
            .<Long>append(new KeyedCodec<>("LastEquipmentSentAtMs", Codec.LONG),
                    AvatarFlightRiderVisualComponent::setLastEquipmentSentAtMs,
                    AvatarFlightRiderVisualComponent::getLastEquipmentSentAtMs)
            .add()
            .build();

    private String ownerUuid = "";
    private String riderEntityUuid = "";
    private boolean riderEntity;
    private String equipmentSignature = "";
    private String hiddenOwnerEquipmentSignature = "";
    private String hiddenOwnerSourceEquipmentSignature = "";
    private long lastEquipmentSentAtMs;

    @Nullable
    public static ComponentType<EntityStore, AvatarFlightRiderVisualComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getAvatarFlightRiderVisualComponentType();
    }

    @Nonnull
    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
    }

    public void setOwnerUuid(@Nullable String ownerUuid) {
        this.ownerUuid = sanitize(ownerUuid);
    }

    @Nonnull
    public String getRiderEntityUuid() {
        return riderEntityUuid == null ? "" : riderEntityUuid;
    }

    public void setRiderEntityUuid(@Nullable String riderEntityUuid) {
        this.riderEntityUuid = sanitize(riderEntityUuid);
    }

    public boolean isRiderEntity() {
        return riderEntity;
    }

    public void setRiderEntity(@Nullable Boolean riderEntity) {
        this.riderEntity = riderEntity != null && riderEntity;
    }

    @Nonnull
    public String getEquipmentSignature() {
        return equipmentSignature == null ? "" : equipmentSignature;
    }

    public void setEquipmentSignature(@Nullable String equipmentSignature) {
        this.equipmentSignature = sanitize(equipmentSignature);
    }

    @Nonnull
    public String getHiddenOwnerEquipmentSignature() {
        return hiddenOwnerEquipmentSignature == null ? "" : hiddenOwnerEquipmentSignature;
    }

    public void setHiddenOwnerEquipmentSignature(@Nullable String hiddenOwnerEquipmentSignature) {
        this.hiddenOwnerEquipmentSignature = sanitize(hiddenOwnerEquipmentSignature);
    }

    @Nonnull
    public String getHiddenOwnerSourceEquipmentSignature() {
        return hiddenOwnerSourceEquipmentSignature == null ? "" : hiddenOwnerSourceEquipmentSignature;
    }

    public void setHiddenOwnerSourceEquipmentSignature(@Nullable String hiddenOwnerSourceEquipmentSignature) {
        this.hiddenOwnerSourceEquipmentSignature = sanitize(hiddenOwnerSourceEquipmentSignature);
    }

    public long getLastEquipmentSentAtMs() {
        return lastEquipmentSentAtMs;
    }

    public void setLastEquipmentSentAtMs(@Nullable Long lastEquipmentSentAtMs) {
        this.lastEquipmentSentAtMs = lastEquipmentSentAtMs == null ? 0L : lastEquipmentSentAtMs;
    }

    @Override
    public AvatarFlightRiderVisualComponent clone() {
        AvatarFlightRiderVisualComponent clone = new AvatarFlightRiderVisualComponent();
        clone.ownerUuid = getOwnerUuid();
        clone.riderEntityUuid = getRiderEntityUuid();
        clone.riderEntity = riderEntity;
        clone.equipmentSignature = getEquipmentSignature();
        clone.hiddenOwnerEquipmentSignature = getHiddenOwnerEquipmentSignature();
        clone.hiddenOwnerSourceEquipmentSignature = getHiddenOwnerSourceEquipmentSignature();
        clone.lastEquipmentSentAtMs = lastEquipmentSentAtMs;
        return clone;
    }

    @Nonnull
    private static String sanitize(@Nullable String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
