package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.npc.movement.MountedGlidePhysicsState;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Mount-side state for the clean-slate Tamework mounted glide controller.
 */
public final class TameworkMountedGlideComponent implements Component<EntityStore> {
    public static final String DEFAULT_GLIDE_CONTROLLER = "TameworkMountedGlide";
    public static final String DEFAULT_GLIDE_STATE = "Ridden";

    public static final BuilderCodec<TameworkMountedGlideComponent> CODEC = BuilderCodec.builder(
            TameworkMountedGlideComponent.class,
            TameworkMountedGlideComponent::new
    )
            .append(new KeyedCodec<>("RiderUuid", Codec.STRING),
                    TameworkMountedGlideComponent::setRiderUuid,
                    TameworkMountedGlideComponent::getRiderUuid)
            .add()
            .append(new KeyedCodec<>("ConfigId", Codec.STRING),
                    TameworkMountedGlideComponent::setConfigId,
                    TameworkMountedGlideComponent::getConfigId)
            .add()
            .append(new KeyedCodec<>("PreviousState", Codec.STRING),
                    TameworkMountedGlideComponent::setPreviousState,
                    TameworkMountedGlideComponent::getPreviousState)
            .add()
            .append(new KeyedCodec<>("PreviousSubState", Codec.STRING),
                    TameworkMountedGlideComponent::setPreviousSubState,
                    TameworkMountedGlideComponent::getPreviousSubState)
            .add()
            .append(new KeyedCodec<>("PreviousMotionController", Codec.STRING),
                    TameworkMountedGlideComponent::setPreviousMotionController,
                    TameworkMountedGlideComponent::getPreviousMotionController)
            .add()
            .append(new KeyedCodec<>("GlideController", Codec.STRING),
                    TameworkMountedGlideComponent::setGlideController,
                    TameworkMountedGlideComponent::getGlideController)
            .add()
            .append(new KeyedCodec<>("GlideState", Codec.STRING),
                    TameworkMountedGlideComponent::setGlideState,
                    TameworkMountedGlideComponent::getGlideState)
            .add()
            .append(new KeyedCodec<>("MountStartMs", Codec.LONG),
                    TameworkMountedGlideComponent::setMountStartMs,
                    TameworkMountedGlideComponent::getMountStartMs)
            .add()
            .append(new KeyedCodec<>("LastInputAtMs", Codec.LONG),
                    TameworkMountedGlideComponent::setLastInputAtMs,
                    TameworkMountedGlideComponent::getLastInputAtMs)
            .add()
            .append(new KeyedCodec<>("LastPacketInputAtMs", Codec.LONG),
                    TameworkMountedGlideComponent::setLastPacketInputAtMs,
                    TameworkMountedGlideComponent::getLastPacketInputAtMs)
            .add()
            .append(new KeyedCodec<>("HasMovementIntent", Codec.BOOLEAN),
                    TameworkMountedGlideComponent::setHasMovementIntent,
                    TameworkMountedGlideComponent::hasMovementIntent)
            .add()
            .append(new KeyedCodec<>("ForwardIntent", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setForwardIntent,
                    TameworkMountedGlideComponent::getForwardIntent)
            .add()
            .append(new KeyedCodec<>("StrafeIntent", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setStrafeIntent,
                    TameworkMountedGlideComponent::getStrafeIntent)
            .add()
            .append(new KeyedCodec<>("HasLookRotation", Codec.BOOLEAN),
                    TameworkMountedGlideComponent::setHasLookRotation,
                    TameworkMountedGlideComponent::hasLookRotation)
            .add()
            .append(new KeyedCodec<>("LookYawDegrees", Codec.FLOAT),
                    TameworkMountedGlideComponent::setLookYawDegrees,
                    TameworkMountedGlideComponent::getLookYawDegrees)
            .add()
            .append(new KeyedCodec<>("LookPitchDegrees", Codec.FLOAT),
                    TameworkMountedGlideComponent::setLookPitchDegrees,
                    TameworkMountedGlideComponent::getLookPitchDegrees)
            .add()
            .append(new KeyedCodec<>("LookRollDegrees", Codec.FLOAT),
                    TameworkMountedGlideComponent::setLookRollDegrees,
                    TameworkMountedGlideComponent::getLookRollDegrees)
            .add()
            .append(new KeyedCodec<>("JumpHeld", Codec.BOOLEAN),
                    TameworkMountedGlideComponent::setJumpHeld,
                    TameworkMountedGlideComponent::isJumpHeld)
            .add()
            .append(new KeyedCodec<>("Sprinting", Codec.BOOLEAN),
                    TameworkMountedGlideComponent::setSprinting,
                    TameworkMountedGlideComponent::isSprinting)
            .add()
            .append(new KeyedCodec<>("Crouching", Codec.BOOLEAN),
                    TameworkMountedGlideComponent::setCrouching,
                    TameworkMountedGlideComponent::isCrouching)
            .add()
            .append(new KeyedCodec<>("GlideSpeed", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setGlideSpeed,
                    TameworkMountedGlideComponent::getGlideSpeed)
            .add()
            .append(new KeyedCodec<>("VerticalVelocity", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setVerticalVelocity,
                    TameworkMountedGlideComponent::getVerticalVelocity)
            .add()
            .append(new KeyedCodec<>("FlapCooldownRemainingSeconds", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setFlapCooldownRemainingSeconds,
                    TameworkMountedGlideComponent::getFlapCooldownRemainingSeconds)
            .add()
            .append(new KeyedCodec<>("BoostRemainingSeconds", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setBoostRemainingSeconds,
                    TameworkMountedGlideComponent::getBoostRemainingSeconds)
            .add()
            .append(new KeyedCodec<>("FlightActive", Codec.BOOLEAN),
                    TameworkMountedGlideComponent::setFlightActive,
                    TameworkMountedGlideComponent::isFlightActive)
            .add()
            .append(new KeyedCodec<>("HasAuthoritativePose", Codec.BOOLEAN),
                    TameworkMountedGlideComponent::setHasAuthoritativePose,
                    TameworkMountedGlideComponent::hasAuthoritativePose)
            .add()
            .append(new KeyedCodec<>("AuthoritativeX", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setAuthoritativeX,
                    TameworkMountedGlideComponent::getAuthoritativeX)
            .add()
            .append(new KeyedCodec<>("AuthoritativeY", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setAuthoritativeY,
                    TameworkMountedGlideComponent::getAuthoritativeY)
            .add()
            .append(new KeyedCodec<>("AuthoritativeZ", Codec.DOUBLE),
                    TameworkMountedGlideComponent::setAuthoritativeZ,
                    TameworkMountedGlideComponent::getAuthoritativeZ)
            .add()
            .append(new KeyedCodec<>("AuthoritativeYaw", Codec.FLOAT),
                    TameworkMountedGlideComponent::setAuthoritativeYaw,
                    TameworkMountedGlideComponent::getAuthoritativeYaw)
            .add()
            .append(new KeyedCodec<>("AuthoritativePitch", Codec.FLOAT),
                    TameworkMountedGlideComponent::setAuthoritativePitch,
                    TameworkMountedGlideComponent::getAuthoritativePitch)
            .add()
            .append(new KeyedCodec<>("AuthoritativeRoll", Codec.FLOAT),
                    TameworkMountedGlideComponent::setAuthoritativeRoll,
                    TameworkMountedGlideComponent::getAuthoritativeRoll)
            .add()
            .build();

    private String riderUuid = "";
    private String configId = "";
    private String previousState = "";
    private String previousSubState = "";
    private String previousMotionController = "";
    private String glideController = DEFAULT_GLIDE_CONTROLLER;
    private String glideState = DEFAULT_GLIDE_STATE;
    private long mountStartMs;
    private long lastInputAtMs;
    private long lastPacketInputAtMs;
    private boolean hasMovementIntent;
    private double forwardIntent;
    private double strafeIntent;
    private boolean hasLookRotation;
    private float lookYawDegrees;
    private float lookPitchDegrees;
    private float lookRollDegrees;
    private boolean jumpHeld;
    private boolean sprinting;
    private boolean crouching;
    private double glideSpeed;
    private double verticalVelocity;
    private double flapCooldownRemainingSeconds;
    private double boostRemainingSeconds;
    private boolean flightActive;
    private boolean hasAuthoritativePose;
    private double authoritativeX;
    private double authoritativeY;
    private double authoritativeZ;
    private float authoritativeYaw;
    private float authoritativePitch;
    private float authoritativeRoll;

    public TameworkMountedGlideComponent() {
    }

    public TameworkMountedGlideComponent(String riderUuid) {
        setRiderUuid(riderUuid);
    }

    public static ComponentType<EntityStore, TameworkMountedGlideComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getMountedGlideComponentType() : null;
    }

    public void initializePhysicsState(@Nonnull TwMountedGlideConfig config) {
        applyPhysicsState(MountedGlidePhysicsState.from(config));
    }

    @Nonnull
    public MountedGlidePhysicsState toPhysicsState() {
        MountedGlidePhysicsState state = new MountedGlidePhysicsState();
        state.setGlideSpeed(glideSpeed);
        state.setVerticalVelocity(verticalVelocity);
        state.setFlapCooldownRemainingSeconds(flapCooldownRemainingSeconds);
        state.setBoostRemainingSeconds(boostRemainingSeconds);
        return state;
    }

    public void applyPhysicsState(@Nonnull MountedGlidePhysicsState state) {
        glideSpeed = state.getGlideSpeed();
        verticalVelocity = state.getVerticalVelocity();
        flapCooldownRemainingSeconds = state.getFlapCooldownRemainingSeconds();
        boostRemainingSeconds = state.getBoostRemainingSeconds();
    }

    public boolean shouldRequestFlap() {
        return jumpHeld && flapCooldownRemainingSeconds <= 0.0;
    }

    public void captureMovementIntent(double forwardIntent, double strafeIntent, long inputAtMs) {
        hasMovementIntent = true;
        setForwardIntent(forwardIntent);
        setStrafeIntent(strafeIntent);
        setLastInputAtMs(inputAtMs);
    }

    public void captureLookRotation(float yawDegrees, float pitchDegrees, float rollDegrees, long inputAtMs) {
        hasLookRotation = true;
        lookYawDegrees = finiteOrZero(yawDegrees);
        lookPitchDegrees = finiteOrZero(pitchDegrees);
        lookRollDegrees = finiteOrZero(rollDegrees);
        setLastInputAtMs(inputAtMs);
    }

    public void captureControls(boolean jumpHeld, boolean sprinting, boolean crouching, long inputAtMs) {
        this.jumpHeld = jumpHeld;
        this.sprinting = sprinting;
        this.crouching = crouching;
        setLastInputAtMs(inputAtMs);
    }

    public void clearInputSnapshot() {
        hasMovementIntent = false;
        forwardIntent = 0.0;
        strafeIntent = 0.0;
        hasLookRotation = false;
        lookYawDegrees = 0.0f;
        lookPitchDegrees = 0.0f;
        lookRollDegrees = 0.0f;
        jumpHeld = false;
        sprinting = false;
        crouching = false;
        lastInputAtMs = 0L;
        lastPacketInputAtMs = 0L;
    }

    public void captureAuthoritativePose(double x,
                                         double y,
                                         double z,
                                         float yaw,
                                         float pitch,
                                         float roll) {
        hasAuthoritativePose = true;
        authoritativeX = finiteOrZero(x);
        authoritativeY = finiteOrZero(y);
        authoritativeZ = finiteOrZero(z);
        authoritativeYaw = finiteOrZero(yaw);
        authoritativePitch = finiteOrZero(pitch);
        authoritativeRoll = finiteOrZero(roll);
    }

    public void clearAuthoritativePose() {
        hasAuthoritativePose = false;
        authoritativeX = 0.0;
        authoritativeY = 0.0;
        authoritativeZ = 0.0;
        authoritativeYaw = 0.0f;
        authoritativePitch = 0.0f;
        authoritativeRoll = 0.0f;
    }

    public String getRiderUuid() { return riderUuid; }
    public void setRiderUuid(String riderUuid) { this.riderUuid = sanitizeString(riderUuid); }
    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = sanitizeString(configId); }
    public String getPreviousState() { return previousState; }
    public void setPreviousState(String previousState) { this.previousState = sanitizeString(previousState); }
    public String getPreviousSubState() { return previousSubState; }
    public void setPreviousSubState(String previousSubState) { this.previousSubState = sanitizeString(previousSubState); }
    public String getPreviousMotionController() { return previousMotionController; }
    public void setPreviousMotionController(String previousMotionController) {
        this.previousMotionController = sanitizeString(previousMotionController);
    }
    public String getGlideController() { return glideController; }
    public void setGlideController(String glideController) {
        this.glideController = sanitizeString(glideController, DEFAULT_GLIDE_CONTROLLER);
    }
    public String getGlideState() { return glideState; }
    public void setGlideState(String glideState) { this.glideState = sanitizeString(glideState, DEFAULT_GLIDE_STATE); }
    public long getMountStartMs() { return mountStartMs; }
    public void setMountStartMs(long mountStartMs) { this.mountStartMs = mountStartMs; }
    public long getLastInputAtMs() { return lastInputAtMs; }
    public void setLastInputAtMs(long lastInputAtMs) { this.lastInputAtMs = lastInputAtMs; }
    public long getLastPacketInputAtMs() { return lastPacketInputAtMs; }
    public void setLastPacketInputAtMs(long lastPacketInputAtMs) { this.lastPacketInputAtMs = lastPacketInputAtMs; }
    public boolean hasMovementIntent() { return hasMovementIntent; }
    public void setHasMovementIntent(boolean hasMovementIntent) { this.hasMovementIntent = hasMovementIntent; }
    public double getForwardIntent() { return forwardIntent; }
    public void setForwardIntent(double forwardIntent) { this.forwardIntent = clampIntent(forwardIntent); }
    public double getStrafeIntent() { return strafeIntent; }
    public void setStrafeIntent(double strafeIntent) { this.strafeIntent = clampIntent(strafeIntent); }
    public boolean hasLookRotation() { return hasLookRotation; }
    public void setHasLookRotation(boolean hasLookRotation) { this.hasLookRotation = hasLookRotation; }
    public float getLookYawDegrees() { return lookYawDegrees; }
    public void setLookYawDegrees(float lookYawDegrees) { this.lookYawDegrees = finiteOrZero(lookYawDegrees); }
    public float getLookPitchDegrees() { return lookPitchDegrees; }
    public void setLookPitchDegrees(float lookPitchDegrees) { this.lookPitchDegrees = finiteOrZero(lookPitchDegrees); }
    public float getLookRollDegrees() { return lookRollDegrees; }
    public void setLookRollDegrees(float lookRollDegrees) { this.lookRollDegrees = finiteOrZero(lookRollDegrees); }
    public boolean isJumpHeld() { return jumpHeld; }
    public void setJumpHeld(boolean jumpHeld) { this.jumpHeld = jumpHeld; }
    public boolean isSprinting() { return sprinting; }
    public void setSprinting(boolean sprinting) { this.sprinting = sprinting; }
    public boolean isCrouching() { return crouching; }
    public void setCrouching(boolean crouching) { this.crouching = crouching; }
    public double getGlideSpeed() { return glideSpeed; }
    public void setGlideSpeed(double glideSpeed) { this.glideSpeed = finiteOrZero(glideSpeed); }
    public double getVerticalVelocity() { return verticalVelocity; }
    public void setVerticalVelocity(double verticalVelocity) { this.verticalVelocity = finiteOrZero(verticalVelocity); }
    public double getFlapCooldownRemainingSeconds() { return flapCooldownRemainingSeconds; }
    public void setFlapCooldownRemainingSeconds(double flapCooldownRemainingSeconds) {
        this.flapCooldownRemainingSeconds = nonNegativeFinite(flapCooldownRemainingSeconds);
    }
    public double getBoostRemainingSeconds() { return boostRemainingSeconds; }
    public void setBoostRemainingSeconds(double boostRemainingSeconds) {
        this.boostRemainingSeconds = nonNegativeFinite(boostRemainingSeconds);
    }
    public boolean isFlightActive() { return flightActive; }
    public void setFlightActive(boolean flightActive) { this.flightActive = flightActive; }
    public boolean hasAuthoritativePose() { return hasAuthoritativePose; }
    public void setHasAuthoritativePose(boolean hasAuthoritativePose) {
        this.hasAuthoritativePose = hasAuthoritativePose;
    }
    public double getAuthoritativeX() { return authoritativeX; }
    public void setAuthoritativeX(double authoritativeX) { this.authoritativeX = finiteOrZero(authoritativeX); }
    public double getAuthoritativeY() { return authoritativeY; }
    public void setAuthoritativeY(double authoritativeY) { this.authoritativeY = finiteOrZero(authoritativeY); }
    public double getAuthoritativeZ() { return authoritativeZ; }
    public void setAuthoritativeZ(double authoritativeZ) { this.authoritativeZ = finiteOrZero(authoritativeZ); }
    public float getAuthoritativeYaw() { return authoritativeYaw; }
    public void setAuthoritativeYaw(float authoritativeYaw) { this.authoritativeYaw = finiteOrZero(authoritativeYaw); }
    public float getAuthoritativePitch() { return authoritativePitch; }
    public void setAuthoritativePitch(float authoritativePitch) { this.authoritativePitch = finiteOrZero(authoritativePitch); }
    public float getAuthoritativeRoll() { return authoritativeRoll; }
    public void setAuthoritativeRoll(float authoritativeRoll) { this.authoritativeRoll = finiteOrZero(authoritativeRoll); }

    @Override
    public TameworkMountedGlideComponent clone() {
        TameworkMountedGlideComponent clone = new TameworkMountedGlideComponent(riderUuid);
        clone.configId = configId;
        clone.previousState = previousState;
        clone.previousSubState = previousSubState;
        clone.previousMotionController = previousMotionController;
        clone.glideController = glideController;
        clone.glideState = glideState;
        clone.mountStartMs = mountStartMs;
        clone.lastInputAtMs = lastInputAtMs;
        clone.lastPacketInputAtMs = lastPacketInputAtMs;
        clone.hasMovementIntent = hasMovementIntent;
        clone.forwardIntent = forwardIntent;
        clone.strafeIntent = strafeIntent;
        clone.hasLookRotation = hasLookRotation;
        clone.lookYawDegrees = lookYawDegrees;
        clone.lookPitchDegrees = lookPitchDegrees;
        clone.lookRollDegrees = lookRollDegrees;
        clone.jumpHeld = jumpHeld;
        clone.sprinting = sprinting;
        clone.crouching = crouching;
        clone.glideSpeed = glideSpeed;
        clone.verticalVelocity = verticalVelocity;
        clone.flapCooldownRemainingSeconds = flapCooldownRemainingSeconds;
        clone.boostRemainingSeconds = boostRemainingSeconds;
        clone.flightActive = flightActive;
        clone.hasAuthoritativePose = hasAuthoritativePose;
        clone.authoritativeX = authoritativeX;
        clone.authoritativeY = authoritativeY;
        clone.authoritativeZ = authoritativeZ;
        clone.authoritativeYaw = authoritativeYaw;
        clone.authoritativePitch = authoritativePitch;
        clone.authoritativeRoll = authoritativeRoll;
        return clone;
    }

    private static String sanitizeString(String value) {
        return sanitizeString(value, "");
    }

    private static String sanitizeString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clampIntent(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static double nonNegativeFinite(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
