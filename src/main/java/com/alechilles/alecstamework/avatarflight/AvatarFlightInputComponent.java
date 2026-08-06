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
            .<Boolean>append(new KeyedCodec<>("JumpHeldForEdge", Codec.BOOLEAN),
                    AvatarFlightInputComponent::setJumpHeldForEdge,
                    AvatarFlightInputComponent::isJumpHeldForEdge)
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
            .<Long>append(new KeyedCodec<>("AirborneSinceMs", Codec.LONG),
                    AvatarFlightInputComponent::setAirborneSinceMs,
                    AvatarFlightInputComponent::getAirborneSinceMs)
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
            .<Long>append(new KeyedCodec<>("AirborneJumpPressQueuedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setAirborneJumpPressQueuedAtMs,
                    AvatarFlightInputComponent::getAirborneJumpPressQueuedAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("ReinsAirbrakeUntilMs", Codec.LONG),
                    AvatarFlightInputComponent::setReinsAirbrakeUntilMs,
                    AvatarFlightInputComponent::getReinsAirbrakeUntilMs)
            .add()
            .<Long>append(new KeyedCodec<>("ReinsAirbrakeActivatedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setReinsAirbrakeActivatedAtMs,
                    AvatarFlightInputComponent::getReinsAirbrakeActivatedAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("ReinsBoostQueuedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setReinsBoostQueuedAtMs,
                    AvatarFlightInputComponent::getReinsBoostQueuedAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("SprintBoostQueuedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setSprintBoostQueuedAtMs,
                    AvatarFlightInputComponent::getSprintBoostQueuedAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("LaunchChargeStartedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setLaunchChargeStartedAtMs,
                    AvatarFlightInputComponent::getLaunchChargeStartedAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("LaunchReleasedAtMs", Codec.LONG),
                    AvatarFlightInputComponent::setLaunchReleasedAtMs,
                    AvatarFlightInputComponent::getLaunchReleasedAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("LaunchHoldMs", Codec.LONG),
                    AvatarFlightInputComponent::setLaunchHoldMs,
                    AvatarFlightInputComponent::getLaunchHoldMs)
            .add()
            .build();

    private double forwardAxis;
    private double strafeAxis;
    private double verticalAxis;
    private boolean jumping;
    private boolean jumpHeldForEdge;
    private boolean crouching;
    private boolean sprinting;
    private boolean onGround = true;
    private long airborneSinceMs;
    private double yawRadians;
    private double pitchRadians;
    private long lastInputAtMs;
    private long reinsFlapQueuedAtMs;
    private long airborneJumpPressQueuedAtMs;
    private long reinsAirbrakeUntilMs;
    private long reinsAirbrakeActivatedAtMs;
    private long reinsBoostQueuedAtMs;
    private long sprintBoostQueuedAtMs;
    private long launchChargeStartedAtMs;
    private long launchReleasedAtMs;
    private long launchHoldMs;

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
        this.jumpHeldForEdge = this.jumping;
    }

    public boolean isJumpHeldForEdge() {
        return jumpHeldForEdge;
    }

    public void setJumpHeldForEdge(@Nullable Boolean jumpHeldForEdge) {
        this.jumpHeldForEdge = jumpHeldForEdge != null && jumpHeldForEdge;
    }

    public void updateJumping(@Nullable Boolean jumping, long nowMs, boolean onGround) {
        updateJumping(jumping, nowMs, onGround, 0L);
    }

    public void updateJumping(@Nullable Boolean jumping, long nowMs, boolean onGround, long airborneActivationDelayMs) {
        boolean next = jumping != null && jumping;
        this.jumping = next;
        this.jumpHeldForEdge = next;
    }

    private boolean canQueueAirborneJumpPress(long nowMs, long airborneActivationDelayMs) {
        if (airborneActivationDelayMs <= 0L) {
            return true;
        }
        return airborneSinceMs != 0L && nowMs - airborneSinceMs >= airborneActivationDelayMs;
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

    public void updateSprinting(@Nullable Boolean sprinting, long nowMs) {
        boolean next = sprinting != null && sprinting;
        if (next && !this.sprinting) {
            sprintBoostQueuedAtMs = nowMs;
        }
        this.sprinting = next;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public void setOnGround(@Nullable Boolean onGround) {
        boolean next = onGround != null && onGround;
        this.onGround = next;
        if (next) {
            airborneSinceMs = 0L;
        }
    }

    public void setOnGround(@Nullable Boolean onGround, long nowMs) {
        boolean next = onGround != null && onGround;
        if (next) {
            airborneSinceMs = 0L;
        } else if (this.onGround || airborneSinceMs == 0L) {
            airborneSinceMs = nowMs;
        }
        this.onGround = next;
    }

    public long getAirborneSinceMs() {
        return airborneSinceMs;
    }

    public void setAirborneSinceMs(@Nullable Long airborneSinceMs) {
        this.airborneSinceMs = airborneSinceMs == null ? 0L : airborneSinceMs;
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

    public long getAirborneJumpPressQueuedAtMs() {
        return airborneJumpPressQueuedAtMs;
    }

    public void setAirborneJumpPressQueuedAtMs(@Nullable Long airborneJumpPressQueuedAtMs) {
        this.airborneJumpPressQueuedAtMs = airborneJumpPressQueuedAtMs == null ? 0L : airborneJumpPressQueuedAtMs;
    }

    public boolean queueAirborneJumpPress(long nowMs, long airborneActivationDelayMs) {
        if (!canQueueAirborneJumpPress(nowMs, airborneActivationDelayMs)) {
            return false;
        }
        airborneJumpPressQueuedAtMs = nowMs;
        return true;
    }

    public long getReinsAirbrakeUntilMs() {
        return reinsAirbrakeUntilMs;
    }

    public void setReinsAirbrakeUntilMs(@Nullable Long reinsAirbrakeUntilMs) {
        this.reinsAirbrakeUntilMs = reinsAirbrakeUntilMs == null ? 0L : reinsAirbrakeUntilMs;
    }

    public long getReinsAirbrakeActivatedAtMs() {
        return reinsAirbrakeActivatedAtMs;
    }

    public void setReinsAirbrakeActivatedAtMs(@Nullable Long reinsAirbrakeActivatedAtMs) {
        this.reinsAirbrakeActivatedAtMs = reinsAirbrakeActivatedAtMs == null ? 0L : reinsAirbrakeActivatedAtMs;
    }

    public long getReinsBoostQueuedAtMs() {
        return reinsBoostQueuedAtMs;
    }

    public void setReinsBoostQueuedAtMs(@Nullable Long reinsBoostQueuedAtMs) {
        this.reinsBoostQueuedAtMs = reinsBoostQueuedAtMs == null ? 0L : reinsBoostQueuedAtMs;
    }

    public long getSprintBoostQueuedAtMs() {
        return sprintBoostQueuedAtMs;
    }

    public void setSprintBoostQueuedAtMs(@Nullable Long sprintBoostQueuedAtMs) {
        this.sprintBoostQueuedAtMs = sprintBoostQueuedAtMs == null ? 0L : sprintBoostQueuedAtMs;
    }

    public long getLaunchChargeStartedAtMs() {
        return launchChargeStartedAtMs;
    }

    public void setLaunchChargeStartedAtMs(@Nullable Long launchChargeStartedAtMs) {
        this.launchChargeStartedAtMs = launchChargeStartedAtMs == null ? 0L : launchChargeStartedAtMs;
    }

    public long getLaunchReleasedAtMs() {
        return launchReleasedAtMs;
    }

    public void setLaunchReleasedAtMs(@Nullable Long launchReleasedAtMs) {
        this.launchReleasedAtMs = launchReleasedAtMs == null ? 0L : launchReleasedAtMs;
    }

    public long getLaunchHoldMs() {
        return launchHoldMs;
    }

    public void setLaunchHoldMs(@Nullable Long launchHoldMs) {
        this.launchHoldMs = Math.max(0L, launchHoldMs == null ? 0L : launchHoldMs);
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

    public boolean consumeAirborneJumpPress(long nowMs, long maxAgeMs) {
        boolean applies = queuedIntentApplies(airborneJumpPressQueuedAtMs, nowMs, maxAgeMs);
        airborneJumpPressQueuedAtMs = 0L;
        return applies;
    }

    public void activateReinsAirbrake(long nowMs, long durationMs) {
        if (!isReinsAirbrakeActive(nowMs)) {
            reinsAirbrakeActivatedAtMs = nowMs;
        }
        reinsAirbrakeUntilMs = Math.max(reinsAirbrakeUntilMs, nowMs + Math.max(0L, durationMs));
    }

    public boolean consumeReinsAirbrakeActivation(long nowMs, long maxAgeMs) {
        boolean applies = queuedIntentApplies(reinsAirbrakeActivatedAtMs, nowMs, maxAgeMs);
        reinsAirbrakeActivatedAtMs = 0L;
        return applies;
    }

    public boolean isReinsAirbrakeActive(long nowMs) {
        return reinsAirbrakeUntilMs != 0L && nowMs <= reinsAirbrakeUntilMs;
    }

    public void queueReinsBoost(long nowMs) {
        reinsBoostQueuedAtMs = nowMs;
    }

    public boolean consumeReinsBoost(long nowMs, long maxAgeMs) {
        boolean applies = queuedIntentApplies(reinsBoostQueuedAtMs, nowMs, maxAgeMs);
        reinsBoostQueuedAtMs = 0L;
        return applies;
    }

    public boolean consumeSprintBoost(long nowMs, long maxAgeMs) {
        boolean applies = queuedIntentApplies(sprintBoostQueuedAtMs, nowMs, maxAgeMs);
        sprintBoostQueuedAtMs = 0L;
        return applies;
    }

    public void beginLaunchCharge(long nowMs) {
        if (!isLaunchCharging() && launchReleasedAtMs == 0L) {
            launchChargeStartedAtMs = nowMs;
        }
    }

    public boolean isLaunchCharging() {
        return launchChargeStartedAtMs != 0L;
    }

    public void cancelLaunchCharge() {
        launchChargeStartedAtMs = 0L;
    }

    public void queueLaunchRelease(long nowMs) {
        if (!isLaunchCharging()) {
            return;
        }
        launchHoldMs = Math.max(0L, nowMs - launchChargeStartedAtMs);
        launchReleasedAtMs = nowMs;
        launchChargeStartedAtMs = 0L;
    }

    public boolean consumeLaunchRelease(long nowMs, long maxAgeMs) {
        boolean applies = queuedIntentApplies(launchReleasedAtMs, nowMs, maxAgeMs);
        launchReleasedAtMs = 0L;
        return applies;
    }

    public boolean isStale(long nowMs, long timeoutMs) {
        return lastInputAtMs == 0L || nowMs - lastInputAtMs > timeoutMs;
    }

    public void clearTransientVerticalIntent() {
        verticalAxis = 0.0;
        jumping = false;
        crouching = false;
    }

    @Override
    public AvatarFlightInputComponent clone() {
        AvatarFlightInputComponent clone = new AvatarFlightInputComponent();
        clone.forwardAxis = forwardAxis;
        clone.strafeAxis = strafeAxis;
        clone.verticalAxis = verticalAxis;
        clone.jumping = jumping;
        clone.jumpHeldForEdge = jumpHeldForEdge;
        clone.crouching = crouching;
        clone.sprinting = sprinting;
        clone.onGround = onGround;
        clone.airborneSinceMs = airborneSinceMs;
        clone.yawRadians = yawRadians;
        clone.pitchRadians = pitchRadians;
        clone.lastInputAtMs = lastInputAtMs;
        clone.reinsFlapQueuedAtMs = reinsFlapQueuedAtMs;
        clone.airborneJumpPressQueuedAtMs = airborneJumpPressQueuedAtMs;
        clone.reinsAirbrakeUntilMs = reinsAirbrakeUntilMs;
        clone.reinsAirbrakeActivatedAtMs = reinsAirbrakeActivatedAtMs;
        clone.reinsBoostQueuedAtMs = reinsBoostQueuedAtMs;
        clone.sprintBoostQueuedAtMs = sprintBoostQueuedAtMs;
        clone.launchChargeStartedAtMs = launchChargeStartedAtMs;
        clone.launchReleasedAtMs = launchReleasedAtMs;
        clone.launchHoldMs = launchHoldMs;
        return clone;
    }

    private static boolean queuedIntentApplies(long queuedAtMs, long nowMs, long maxAgeMs) {
        if (queuedAtMs == 0L) {
            return false;
        }
        return maxAgeMs <= 0L || nowMs - queuedAtMs <= maxAgeMs;
    }

    private static double clampAxis(@Nullable Double value) {
        double finite = finiteOrZero(value);
        return Math.max(-1.0, Math.min(1.0, finite));
    }

    private static double finiteOrZero(@Nullable Double value) {
        return value != null && Double.isFinite(value) ? value : 0.0;
    }
}
