package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Stores the latest packet-derived movement intent for avatar flight.
 */
public final class AvatarFlightInputComponent implements Component<EntityStore> {
    public static final BuilderCodec<AvatarFlightInputComponent> CODEC = BuilderCodec.builder(
            AvatarFlightInputComponent.class,
            AvatarFlightInputComponent::new
    )
            .<Double>append(new KeyedCodec<>("ForwardAxis", Codec.DOUBLE),
                    AvatarFlightInputComponent::setForwardAxis,
                    AvatarFlightInputComponent::getForwardAxis)
            .add()
            .<Double>append(new KeyedCodec<>("StrafeAxis", Codec.DOUBLE),
                    AvatarFlightInputComponent::setStrafeAxis,
                    AvatarFlightInputComponent::getStrafeAxis)
            .add()
            .<Double>append(new KeyedCodec<>("VerticalAxis", Codec.DOUBLE),
                    AvatarFlightInputComponent::setVerticalAxis,
                    AvatarFlightInputComponent::getVerticalAxis)
            .add()
            .<Boolean>append(new KeyedCodec<>("Jumping", Codec.BOOLEAN),
                    AvatarFlightInputComponent::setJumping,
                    AvatarFlightInputComponent::isJumping)
            .add()
            .<Boolean>append(new KeyedCodec<>("Crouching", Codec.BOOLEAN),
                    AvatarFlightInputComponent::setCrouching,
                    AvatarFlightInputComponent::isCrouching)
            .add()
            .<Boolean>append(new KeyedCodec<>("Sprinting", Codec.BOOLEAN),
                    AvatarFlightInputComponent::setSprinting,
                    AvatarFlightInputComponent::isSprinting)
            .add()
            .<Boolean>append(new KeyedCodec<>("OnGround", Codec.BOOLEAN),
                    AvatarFlightInputComponent::setOnGround,
                    AvatarFlightInputComponent::isOnGround)
            .add()
            .<Double>append(new KeyedCodec<>("YawRadians", Codec.DOUBLE),
                    AvatarFlightInputComponent::setYawRadians,
                    AvatarFlightInputComponent::getYawRadians)
            .add()
            .<Double>append(new KeyedCodec<>("PitchRadians", Codec.DOUBLE),
                    AvatarFlightInputComponent::setPitchRadians,
                    AvatarFlightInputComponent::getPitchRadians)
            .add()
            .<Long>append(new KeyedCodec<>("LastInputAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setLastInputAtMs,
                    AvatarFlightInputComponent::getLastInputAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("ReinsFlapQueuedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setReinsFlapQueuedAtMs,
                    AvatarFlightInputComponent::getReinsFlapQueuedAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("ReinsAirbrakeUntilMs", Codec.LONG),
                    AvatarFlightInputComponent::setReinsAirbrakeUntilMs,
                    AvatarFlightInputComponent::getReinsAirbrakeUntilMs)
            .add()
            .<Long>append(new KeyedCodec<>("ReinsBoostQueuedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setReinsBoostQueuedAtMs,
                    AvatarFlightInputComponent::getReinsBoostQueuedAtMs)
            .add()
            .build();

    private double forwardAxis;
    private double strafeAxis;
    private double verticalAxis;
    private boolean jumping;
    private boolean crouching;
    private boolean sprinting;
    private boolean onGround = true;
    private double yawRadians;
    private double pitchRadians;
    private long lastInputAtMs;
    private long reinsFlapQueuedAtMs;
    private long reinsAirbrakeUntilMs;
    private long reinsBoostQueuedAtMs;

    @Nullable
    public static ComponentType<EntityStore, AvatarFlightInputComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getAvatarFlightInputComponentType();
    }

    public double getForwardAxis() {
        return forwardAxis;
    }

    public void setForwardAxis(@Nullable Double forwardAxis) {
        this.forwardAxis = clampAxis(forwardAxis);
    }

    public double getStrafeAxis() {
        return strafeAxis;
    }

    public void setStrafeAxis(@Nullable Double strafeAxis) {
        this.strafeAxis = clampAxis(strafeAxis);
    }

    public double getVerticalAxis() {
        return verticalAxis;
    }

    public void setVerticalAxis(@Nullable Double verticalAxis) {
        this.verticalAxis = clampAxis(verticalAxis);
    }

    public boolean isJumping() {
        return jumping;
    }

    public void setJumping(@Nullable Boolean jumping) {
        this.jumping = jumping != null && jumping;
    }

    public boolean isCrouching() {
        return crouching;
    }

    public void setCrouching(@Nullable Boolean crouching) {
        this.crouching = crouching != null && crouching;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public void setSprinting(@Nullable Boolean sprinting) {
        this.sprinting = sprinting != null && sprinting;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public void setOnGround(@Nullable Boolean onGround) {
        this.onGround = onGround != null && onGround;
    }

    public double getYawRadians() {
        return yawRadians;
    }

    public void setYawRadians(@Nullable Double yawRadians) {
        this.yawRadians = finiteOrZero(yawRadians);
    }

    public double getPitchRadians() {
        return pitchRadians;
    }

    public void setPitchRadians(@Nullable Double pitchRadians) {
        this.pitchRadians = finiteOrZero(pitchRadians);
    }

    public long getLastInputAtMs() {
        return lastInputAtMs;
    }

    public void setLastInputAtMs(@Nullable Long lastInputAtMs) {
        this.lastInputAtMs = lastInputAtMs == null ? 0L : lastInputAtMs;
    }

    public long getReinsFlapQueuedAtMs() {
        return reinsFlapQueuedAtMs;
    }

    public void setReinsFlapQueuedAtMs(@Nullable Long reinsFlapQueuedAtMs) {
        this.reinsFlapQueuedAtMs = reinsFlapQueuedAtMs == null ? 0L : reinsFlapQueuedAtMs;
    }

    public long getReinsAirbrakeUntilMs() {
        return reinsAirbrakeUntilMs;
    }

    public void setReinsAirbrakeUntilMs(@Nullable Long reinsAirbrakeUntilMs) {
        this.reinsAirbrakeUntilMs = reinsAirbrakeUntilMs == null ? 0L : reinsAirbrakeUntilMs;
    }

    public long getReinsBoostQueuedAtMs() {
        return reinsBoostQueuedAtMs;
    }

    public void setReinsBoostQueuedAtMs(@Nullable Long reinsBoostQueuedAtMs) {
        this.reinsBoostQueuedAtMs = reinsBoostQueuedAtMs == null ? 0L : reinsBoostQueuedAtMs;
    }

    public void queueReinsFlap(long nowMs) {
        reinsFlapQueuedAtMs = nowMs;
    }

    public boolean consumeReinsFlap() {
        if (reinsFlapQueuedAtMs == 0L) {
            return false;
        }
        reinsFlapQueuedAtMs = 0L;
        return true;
    }

    public boolean consumeReinsFlap(long nowMs, long maxAgeMs) {
        if (reinsFlapQueuedAtMs == 0L) {
            return false;
        }
        long queuedAtMs = reinsFlapQueuedAtMs;
        reinsFlapQueuedAtMs = 0L;
        return maxAgeMs <= 0L || nowMs - queuedAtMs <= maxAgeMs;
    }

    public void activateReinsAirbrake(long nowMs, long durationMs) {
        reinsAirbrakeUntilMs = Math.max(reinsAirbrakeUntilMs, nowMs + Math.max(0L, durationMs));
    }

    public boolean isReinsAirbrakeActive(long nowMs) {
        return reinsAirbrakeUntilMs != 0L && nowMs <= reinsAirbrakeUntilMs;
    }

    public void queueReinsBoost(long nowMs) {
        reinsBoostQueuedAtMs = nowMs;
    }

    public boolean consumeReinsBoost(long nowMs, long maxAgeMs) {
        if (reinsBoostQueuedAtMs == 0L) {
            return false;
        }
        long queuedAtMs = reinsBoostQueuedAtMs;
        reinsBoostQueuedAtMs = 0L;
        return maxAgeMs <= 0L || nowMs - queuedAtMs <= maxAgeMs;
    }

    public boolean isStale(long nowMs, long timeoutMs) {
        return lastInputAtMs == 0L || nowMs - lastInputAtMs > timeoutMs;
    }

    public void clearTransientVerticalIntent() {
        verticalAxis = 0.0;
        jumping = false;
        crouching = false;
        sprinting = false;
    }

    @Override
    public AvatarFlightInputComponent clone() {
        AvatarFlightInputComponent clone = new AvatarFlightInputComponent();
        clone.forwardAxis = forwardAxis;
        clone.strafeAxis = strafeAxis;
        clone.verticalAxis = verticalAxis;
        clone.jumping = jumping;
        clone.crouching = crouching;
        clone.sprinting = sprinting;
        clone.onGround = onGround;
        clone.yawRadians = yawRadians;
        clone.pitchRadians = pitchRadians;
        clone.lastInputAtMs = lastInputAtMs;
        clone.reinsFlapQueuedAtMs = reinsFlapQueuedAtMs;
        clone.reinsAirbrakeUntilMs = reinsAirbrakeUntilMs;
        clone.reinsBoostQueuedAtMs = reinsBoostQueuedAtMs;
        return clone;
    }

    private static double clampAxis(@Nullable Double value) {
        double finite = finiteOrZero(value);
        return Math.max(-1.0, Math.min(1.0, finite));
    }

    private static double finiteOrZero(@Nullable Double value) {
        return value != null && Double.isFinite(value) ? value : 0.0;
    }
}
