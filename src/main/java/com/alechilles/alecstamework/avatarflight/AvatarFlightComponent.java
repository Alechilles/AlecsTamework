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
 * Marks a transformed player as controlled by the avatar-flight prototype.
 */
public final class AvatarFlightComponent implements Component<EntityStore> {
    public static final BuilderCodec<AvatarFlightComponent> CODEC = BuilderCodec.builder(
            AvatarFlightComponent.class,
            AvatarFlightComponent::new
    )
            .<String>append(new KeyedCodec<>("ConfigId", Codec.STRING),
                    AvatarFlightComponent::setConfigId,
                    AvatarFlightComponent::getConfigId)
            .add()
            .<String>append(new KeyedCodec<>("Mode", Codec.STRING),
                    AvatarFlightComponent::setModeName,
                    AvatarFlightComponent::getModeName)
            .add()
            .<Double>append(new KeyedCodec<>("VelocityX", Codec.DOUBLE),
                    AvatarFlightComponent::setVelocityX,
                    AvatarFlightComponent::getVelocityX)
            .add()
            .<Double>append(new KeyedCodec<>("VelocityY", Codec.DOUBLE),
                    AvatarFlightComponent::setVelocityY,
                    AvatarFlightComponent::getVelocityY)
            .add()
            .<Double>append(new KeyedCodec<>("VelocityZ", Codec.DOUBLE),
                    AvatarFlightComponent::setVelocityZ,
                    AvatarFlightComponent::getVelocityZ)
            .add()
            .<Long>append(new KeyedCodec<>("NextJumpAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextJumpAtMs,
                    AvatarFlightComponent::getNextJumpAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextBoostAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextBoostAtMs,
                    AvatarFlightComponent::getNextBoostAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("EnabledAtMs", Codec.LONG),
                    AvatarFlightComponent::setEnabledAtMs,
                    AvatarFlightComponent::getEnabledAtMs)
            .add()
            .<Boolean>append(new KeyedCodec<>("ClientFlyingSynced", Codec.BOOLEAN),
                    AvatarFlightComponent::setClientFlyingSynced,
                    AvatarFlightComponent::isClientFlyingSynced)
            .add()
            .build();

    private String configId = "";
    private AvatarFlightMode mode = AvatarFlightMode.GROUNDED;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private long nextJumpAtMs;
    private long nextBoostAtMs;
    private long enabledAtMs;
    private boolean clientFlyingSynced;

    public AvatarFlightComponent() {
    }

    public AvatarFlightComponent(@Nullable String configId, long enabledAtMs) {
        setConfigId(configId);
        this.enabledAtMs = enabledAtMs;
    }

    @Nullable
    public static ComponentType<EntityStore, AvatarFlightComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getAvatarFlightComponentType();
    }

    @Nonnull
    public String getConfigId() {
        return configId == null ? "" : configId;
    }

    public void setConfigId(@Nullable String configId) {
        this.configId = configId == null || configId.isBlank() ? "" : configId.trim();
    }

    @Nonnull
    public AvatarFlightMode getMode() {
        return mode == null ? AvatarFlightMode.GROUNDED : mode;
    }

    public void setMode(@Nullable AvatarFlightMode mode) {
        this.mode = mode == null ? AvatarFlightMode.GROUNDED : mode;
    }

    @Nonnull
    public String getModeName() {
        return getMode().name();
    }

    public void setModeName(@Nullable String value) {
        if (value == null || value.isBlank()) {
            mode = AvatarFlightMode.GROUNDED;
            return;
        }
        try {
            mode = AvatarFlightMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            mode = AvatarFlightMode.GROUNDED;
        }
    }

    public double getVelocityX() {
        return velocityX;
    }

    public void setVelocityX(@Nullable Double velocityX) {
        this.velocityX = finiteOrZero(velocityX);
    }

    public double getVelocityY() {
        return velocityY;
    }

    public void setVelocityY(@Nullable Double velocityY) {
        this.velocityY = finiteOrZero(velocityY);
    }

    public double getVelocityZ() {
        return velocityZ;
    }

    public void setVelocityZ(@Nullable Double velocityZ) {
        this.velocityZ = finiteOrZero(velocityZ);
    }

    public long getNextJumpAtMs() {
        return nextJumpAtMs;
    }

    public void setNextJumpAtMs(@Nullable Long nextJumpAtMs) {
        this.nextJumpAtMs = nextJumpAtMs == null ? 0L : nextJumpAtMs;
    }

    public long getNextBoostAtMs() {
        return nextBoostAtMs;
    }

    public void setNextBoostAtMs(@Nullable Long nextBoostAtMs) {
        this.nextBoostAtMs = nextBoostAtMs == null ? 0L : nextBoostAtMs;
    }

    public long getEnabledAtMs() {
        return enabledAtMs;
    }

    public void setEnabledAtMs(@Nullable Long enabledAtMs) {
        this.enabledAtMs = enabledAtMs == null ? 0L : enabledAtMs;
    }

    public boolean isClientFlyingSynced() {
        return clientFlyingSynced;
    }

    public void setClientFlyingSynced(@Nullable Boolean clientFlyingSynced) {
        this.clientFlyingSynced = clientFlyingSynced != null && clientFlyingSynced;
    }

    public void setVelocity(double x, double y, double z) {
        velocityX = finiteOrZero(x);
        velocityY = finiteOrZero(y);
        velocityZ = finiteOrZero(z);
    }

    @Override
    public AvatarFlightComponent clone() {
        AvatarFlightComponent clone = new AvatarFlightComponent(configId, enabledAtMs);
        clone.mode = getMode();
        clone.velocityX = velocityX;
        clone.velocityY = velocityY;
        clone.velocityZ = velocityZ;
        clone.nextJumpAtMs = nextJumpAtMs;
        clone.nextBoostAtMs = nextBoostAtMs;
        clone.clientFlyingSynced = clientFlyingSynced;
        return clone;
    }

    private static double finiteOrZero(@Nullable Double value) {
        return value != null && Double.isFinite(value) ? value : 0.0;
    }
}
