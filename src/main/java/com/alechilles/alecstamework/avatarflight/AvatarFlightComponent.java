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
            .<Double>append(new KeyedCodec<>("HudPitchRadians", Codec.DOUBLE),
                    AvatarFlightComponent::setHudPitchRadians,
                    AvatarFlightComponent::getHudPitchRadians)
            .add()
            .<Double>append(new KeyedCodec<>("HudTargetSpeedRatio", Codec.DOUBLE),
                    AvatarFlightComponent::setHudTargetSpeedRatio,
                    AvatarFlightComponent::getHudTargetSpeedRatio)
            .add()
            .<Double>append(new KeyedCodec<>("DiveLoad", Codec.DOUBLE),
                    AvatarFlightComponent::setDiveLoad,
                    AvatarFlightComponent::getDiveLoad)
            .add()
            .<Double>append(new KeyedCodec<>("ClimbLoad", Codec.DOUBLE),
                    AvatarFlightComponent::setClimbLoad,
                    AvatarFlightComponent::getClimbLoad)
            .add()
            .<Long>append(new KeyedCodec<>("NextJumpAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextJumpAtMs,
                    AvatarFlightComponent::getNextJumpAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextBoostAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextBoostAtMs,
                    AvatarFlightComponent::getNextBoostAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextLaunchAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextLaunchAtMs,
                    AvatarFlightComponent::getNextLaunchAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("EnabledAtMs", Codec.LONG),
                    AvatarFlightComponent::setEnabledAtMs,
                    AvatarFlightComponent::getEnabledAtMs)
            .add()
            .<Double>append(new KeyedCodec<>("VigourCharges", Codec.DOUBLE),
                    AvatarFlightComponent::setVigourCharges,
                    AvatarFlightComponent::getVigourCharges)
            .add()
            .<Long>append(new KeyedCodec<>("LastVigourUpdateAtMs", Codec.LONG),
                    AvatarFlightComponent::setLastVigourUpdateAtMs,
                    AvatarFlightComponent::getLastVigourUpdateAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("VigourRechargeBlockedUntilMs", Codec.LONG),
                    AvatarFlightComponent::setVigourRechargeBlockedUntilMs,
                    AvatarFlightComponent::getVigourRechargeBlockedUntilMs)
            .add()
            .<String>append(new KeyedCodec<>("VigourRechargeMode", Codec.STRING),
                    AvatarFlightComponent::setVigourRechargeMode,
                    AvatarFlightComponent::getVigourRechargeMode)
            .add()
            .<Boolean>append(new KeyedCodec<>("ClientFlyingSynced", Codec.BOOLEAN),
                    AvatarFlightComponent::setClientFlyingSynced,
                    AvatarFlightComponent::isClientFlyingSynced)
            .add()
            .<String>append(new KeyedCodec<>("MovementAnimationId", Codec.STRING),
                    AvatarFlightComponent::setMovementAnimationId,
                    AvatarFlightComponent::getMovementAnimationId)
            .add()
            .<Long>append(new KeyedCodec<>("NextMovementAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextMovementAnimationAtMs,
                    AvatarFlightComponent::getNextMovementAnimationAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextSuppressedAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextSuppressedAnimationAtMs,
                    AvatarFlightComponent::getNextSuppressedAnimationAtMs)
            .add()
            .<String>append(new KeyedCodec<>("PitchPoseAnimationId", Codec.STRING),
                    AvatarFlightComponent::setPitchPoseAnimationId,
                    AvatarFlightComponent::getPitchPoseAnimationId)
            .add()
            .<Long>append(new KeyedCodec<>("NextPitchPoseAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextPitchPoseAnimationAtMs,
                    AvatarFlightComponent::getNextPitchPoseAnimationAtMs)
            .add()
            .<String>append(new KeyedCodec<>("RollPoseAnimationId", Codec.STRING),
                    AvatarFlightComponent::setRollPoseAnimationId,
                    AvatarFlightComponent::getRollPoseAnimationId)
            .add()
            .<Long>append(new KeyedCodec<>("NextRollPoseAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextRollPoseAnimationAtMs,
                    AvatarFlightComponent::getNextRollPoseAnimationAtMs)
            .add()
            .build();

    private String configId = "";
    private AvatarFlightMode mode = AvatarFlightMode.GROUNDED;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private double hudPitchRadians;
    private double hudTargetSpeedRatio;
    private double diveLoad;
    private double climbLoad;
    private long nextJumpAtMs;
    private long nextBoostAtMs;
    private long nextLaunchAtMs;
    private long enabledAtMs;
    private double vigourCharges;
    private long lastVigourUpdateAtMs;
    private long vigourRechargeBlockedUntilMs;
    private String vigourRechargeMode = AvatarFlightVigourService.RechargeMode.NONE.name();
    private boolean clientFlyingSynced;
    private String movementAnimationId = "";
    private long nextMovementAnimationAtMs;
    private long nextSuppressedAnimationAtMs;
    private String pitchPoseAnimationId = "";
    private long nextPitchPoseAnimationAtMs;
    private String rollPoseAnimationId = "";
    private long nextRollPoseAnimationAtMs;

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

    public double getHudPitchRadians() {
        return finiteOrZero(hudPitchRadians);
    }

    public void setHudPitchRadians(@Nullable Double hudPitchRadians) {
        this.hudPitchRadians = finiteOrZero(hudPitchRadians);
    }

    public double getHudTargetSpeedRatio() {
        return clamp01(finiteOrZero(hudTargetSpeedRatio));
    }

    public void setHudTargetSpeedRatio(@Nullable Double hudTargetSpeedRatio) {
        this.hudTargetSpeedRatio = clamp01(finiteOrZero(hudTargetSpeedRatio));
    }

    public double getDiveLoad() {
        return clamp01(finiteOrZero(diveLoad));
    }

    public void setDiveLoad(@Nullable Double diveLoad) {
        this.diveLoad = clamp01(finiteOrZero(diveLoad));
    }

    public double getClimbLoad() {
        return clamp01(finiteOrZero(climbLoad));
    }

    public void setClimbLoad(@Nullable Double climbLoad) {
        this.climbLoad = clamp01(finiteOrZero(climbLoad));
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

    public long getNextLaunchAtMs() {
        return nextLaunchAtMs;
    }

    public void setNextLaunchAtMs(@Nullable Long nextLaunchAtMs) {
        this.nextLaunchAtMs = nextLaunchAtMs == null ? 0L : nextLaunchAtMs;
    }

    public long getEnabledAtMs() {
        return enabledAtMs;
    }

    public void setEnabledAtMs(@Nullable Long enabledAtMs) {
        this.enabledAtMs = enabledAtMs == null ? 0L : enabledAtMs;
    }

    public double getVigourCharges() {
        return finiteOrZero(vigourCharges);
    }

    public void setVigourCharges(@Nullable Double vigourCharges) {
        this.vigourCharges = finiteOrZero(vigourCharges);
    }

    public long getLastVigourUpdateAtMs() {
        return lastVigourUpdateAtMs;
    }

    public void setLastVigourUpdateAtMs(@Nullable Long lastVigourUpdateAtMs) {
        this.lastVigourUpdateAtMs = lastVigourUpdateAtMs == null ? 0L : lastVigourUpdateAtMs;
    }

    public long getVigourRechargeBlockedUntilMs() {
        return vigourRechargeBlockedUntilMs;
    }

    public void setVigourRechargeBlockedUntilMs(@Nullable Long vigourRechargeBlockedUntilMs) {
        this.vigourRechargeBlockedUntilMs = vigourRechargeBlockedUntilMs == null ? 0L : vigourRechargeBlockedUntilMs;
    }

    @Nonnull
    public String getVigourRechargeMode() {
        return vigourRechargeMode == null || vigourRechargeMode.isBlank()
                ? AvatarFlightVigourService.RechargeMode.NONE.name()
                : vigourRechargeMode;
    }

    public void setVigourRechargeMode(@Nullable String vigourRechargeMode) {
        if (vigourRechargeMode == null || vigourRechargeMode.isBlank()) {
            this.vigourRechargeMode = AvatarFlightVigourService.RechargeMode.NONE.name();
            return;
        }
        String normalized = vigourRechargeMode.trim().toUpperCase(java.util.Locale.ROOT);
        for (AvatarFlightVigourService.RechargeMode mode : AvatarFlightVigourService.RechargeMode.values()) {
            if (mode.name().equals(normalized)) {
                this.vigourRechargeMode = normalized;
                return;
            }
        }
        this.vigourRechargeMode = AvatarFlightVigourService.RechargeMode.NONE.name();
    }

    public boolean isClientFlyingSynced() {
        return clientFlyingSynced;
    }

    public void setClientFlyingSynced(@Nullable Boolean clientFlyingSynced) {
        this.clientFlyingSynced = clientFlyingSynced != null && clientFlyingSynced;
    }

    @Nonnull
    public String getMovementAnimationId() {
        return movementAnimationId == null ? "" : movementAnimationId;
    }

    public void setMovementAnimationId(@Nullable String movementAnimationId) {
        this.movementAnimationId = movementAnimationId == null ? "" : movementAnimationId.trim();
    }

    public long getNextMovementAnimationAtMs() {
        return nextMovementAnimationAtMs;
    }

    public void setNextMovementAnimationAtMs(@Nullable Long nextMovementAnimationAtMs) {
        this.nextMovementAnimationAtMs = nextMovementAnimationAtMs == null ? 0L : nextMovementAnimationAtMs;
    }

    public long getNextSuppressedAnimationAtMs() {
        return nextSuppressedAnimationAtMs;
    }

    public void setNextSuppressedAnimationAtMs(@Nullable Long nextSuppressedAnimationAtMs) {
        this.nextSuppressedAnimationAtMs = nextSuppressedAnimationAtMs == null ? 0L : nextSuppressedAnimationAtMs;
    }

    @Nonnull
    public String getPitchPoseAnimationId() {
        return pitchPoseAnimationId == null ? "" : pitchPoseAnimationId;
    }

    public void setPitchPoseAnimationId(@Nullable String pitchPoseAnimationId) {
        this.pitchPoseAnimationId = pitchPoseAnimationId == null ? "" : pitchPoseAnimationId.trim();
    }

    public long getNextPitchPoseAnimationAtMs() {
        return nextPitchPoseAnimationAtMs;
    }

    public void setNextPitchPoseAnimationAtMs(@Nullable Long nextPitchPoseAnimationAtMs) {
        this.nextPitchPoseAnimationAtMs = nextPitchPoseAnimationAtMs == null ? 0L : nextPitchPoseAnimationAtMs;
    }

    @Nonnull
    public String getRollPoseAnimationId() {
        return rollPoseAnimationId == null ? "" : rollPoseAnimationId;
    }

    public void setRollPoseAnimationId(@Nullable String rollPoseAnimationId) {
        this.rollPoseAnimationId = rollPoseAnimationId == null ? "" : rollPoseAnimationId.trim();
    }

    public long getNextRollPoseAnimationAtMs() {
        return nextRollPoseAnimationAtMs;
    }

    public void setNextRollPoseAnimationAtMs(@Nullable Long nextRollPoseAnimationAtMs) {
        this.nextRollPoseAnimationAtMs = nextRollPoseAnimationAtMs == null ? 0L : nextRollPoseAnimationAtMs;
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
        clone.hudPitchRadians = getHudPitchRadians();
        clone.hudTargetSpeedRatio = getHudTargetSpeedRatio();
        clone.diveLoad = getDiveLoad();
        clone.climbLoad = getClimbLoad();
        clone.nextJumpAtMs = nextJumpAtMs;
        clone.nextBoostAtMs = nextBoostAtMs;
        clone.nextLaunchAtMs = nextLaunchAtMs;
        clone.vigourCharges = getVigourCharges();
        clone.lastVigourUpdateAtMs = lastVigourUpdateAtMs;
        clone.vigourRechargeBlockedUntilMs = vigourRechargeBlockedUntilMs;
        clone.vigourRechargeMode = getVigourRechargeMode();
        clone.clientFlyingSynced = clientFlyingSynced;
        clone.movementAnimationId = getMovementAnimationId();
        clone.nextMovementAnimationAtMs = nextMovementAnimationAtMs;
        clone.nextSuppressedAnimationAtMs = nextSuppressedAnimationAtMs;
        clone.pitchPoseAnimationId = getPitchPoseAnimationId();
        clone.nextPitchPoseAnimationAtMs = nextPitchPoseAnimationAtMs;
        clone.rollPoseAnimationId = getRollPoseAnimationId();
        clone.nextRollPoseAnimationAtMs = nextRollPoseAnimationAtMs;
        return clone;
    }

    private static double finiteOrZero(@Nullable Double value) {
        return value != null && Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
