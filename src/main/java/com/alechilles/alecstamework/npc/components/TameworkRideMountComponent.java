package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores mount-side Tamework ride linkage, restore data, and the latest rider input snapshot.
 */
public final class TameworkRideMountComponent implements Component<EntityStore> {
    public static final String DEFAULT_GROUND_CONTROLLER = "Walk";
    public static final String DEFAULT_FLIGHT_CONTROLLER = "TameworkFly";
    public static final String DEFAULT_RIDE_STATE = "Ridden";

    public static final BuilderCodec<TameworkRideMountComponent> CODEC = BuilderCodec.builder(
            TameworkRideMountComponent.class,
            TameworkRideMountComponent::new
    )
        .append(
            new KeyedCodec<>("RiderUuid", Codec.STRING),
            TameworkRideMountComponent::setRiderUuid,
            TameworkRideMountComponent::getRiderUuid
        )
        .add()
        .append(
            new KeyedCodec<>("PreviousState", Codec.STRING),
            TameworkRideMountComponent::setPreviousState,
            TameworkRideMountComponent::getPreviousState
        )
        .add()
        .append(
            new KeyedCodec<>("PreviousSubState", Codec.STRING),
            TameworkRideMountComponent::setPreviousSubState,
            TameworkRideMountComponent::getPreviousSubState
        )
        .add()
        .append(
            new KeyedCodec<>("PreviousMotionController", Codec.STRING),
            TameworkRideMountComponent::setPreviousMotionController,
            TameworkRideMountComponent::getPreviousMotionController
        )
        .add()
        .append(
            new KeyedCodec<>("GroundController", Codec.STRING),
            TameworkRideMountComponent::setGroundController,
            TameworkRideMountComponent::getGroundController
        )
        .add()
        .append(
            new KeyedCodec<>("FlightController", Codec.STRING),
            TameworkRideMountComponent::setFlightController,
            TameworkRideMountComponent::getFlightController
        )
        .add()
        .append(
            new KeyedCodec<>("RideState", Codec.STRING),
            TameworkRideMountComponent::setRideState,
            TameworkRideMountComponent::getRideState
        )
        .add()
        .append(
            new KeyedCodec<>("AnchorX", Codec.DOUBLE),
            TameworkRideMountComponent::setAnchorX,
            TameworkRideMountComponent::getAnchorX
        )
        .add()
        .append(
            new KeyedCodec<>("AnchorY", Codec.DOUBLE),
            TameworkRideMountComponent::setAnchorY,
            TameworkRideMountComponent::getAnchorY
        )
        .add()
        .append(
            new KeyedCodec<>("AnchorZ", Codec.DOUBLE),
            TameworkRideMountComponent::setAnchorZ,
            TameworkRideMountComponent::getAnchorZ
        )
        .add()
        .append(
            new KeyedCodec<>("MountStartMs", Codec.LONG),
            TameworkRideMountComponent::setMountStartMs,
            TameworkRideMountComponent::getMountStartMs
        )
        .add()
        .append(
            new KeyedCodec<>("LastInputAtMs", Codec.LONG),
            TameworkRideMountComponent::setLastInputAtMs,
            TameworkRideMountComponent::getLastInputAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("HasWishMovement", Codec.BOOLEAN),
            TameworkRideMountComponent::setHasWishMovement,
            TameworkRideMountComponent::hasWishMovement
        )
        .add()
        .append(
            new KeyedCodec<>("WishX", Codec.DOUBLE),
            TameworkRideMountComponent::setWishX,
            TameworkRideMountComponent::getWishX
        )
        .add()
        .append(
            new KeyedCodec<>("WishY", Codec.DOUBLE),
            TameworkRideMountComponent::setWishY,
            TameworkRideMountComponent::getWishY
        )
        .add()
        .append(
            new KeyedCodec<>("WishZ", Codec.DOUBLE),
            TameworkRideMountComponent::setWishZ,
            TameworkRideMountComponent::getWishZ
        )
        .add()
        .append(
            new KeyedCodec<>("RiderBackwardBrakeInput", Codec.BOOLEAN),
            TameworkRideMountComponent::setRiderBackwardBrakeInput,
            TameworkRideMountComponent::isRiderBackwardBrakeInput
        )
        .add()
        .append(
            new KeyedCodec<>("HasBodyRotation", Codec.BOOLEAN),
            TameworkRideMountComponent::setHasBodyRotation,
            TameworkRideMountComponent::hasBodyRotation
        )
        .add()
        .append(
            new KeyedCodec<>("BodyYaw", Codec.FLOAT),
            TameworkRideMountComponent::setBodyYaw,
            TameworkRideMountComponent::getBodyYaw
        )
        .add()
        .append(
            new KeyedCodec<>("BodyPitch", Codec.FLOAT),
            TameworkRideMountComponent::setBodyPitch,
            TameworkRideMountComponent::getBodyPitch
        )
        .add()
        .append(
            new KeyedCodec<>("BodyRoll", Codec.FLOAT),
            TameworkRideMountComponent::setBodyRoll,
            TameworkRideMountComponent::getBodyRoll
        )
        .add()
        .append(
            new KeyedCodec<>("HasHeadRotation", Codec.BOOLEAN),
            TameworkRideMountComponent::setHasHeadRotation,
            TameworkRideMountComponent::hasHeadRotation
        )
        .add()
        .append(
            new KeyedCodec<>("HeadYaw", Codec.FLOAT),
            TameworkRideMountComponent::setHeadYaw,
            TameworkRideMountComponent::getHeadYaw
        )
        .add()
        .append(
            new KeyedCodec<>("HeadPitch", Codec.FLOAT),
            TameworkRideMountComponent::setHeadPitch,
            TameworkRideMountComponent::getHeadPitch
        )
        .add()
        .append(
            new KeyedCodec<>("HeadRoll", Codec.FLOAT),
            TameworkRideMountComponent::setHeadRoll,
            TameworkRideMountComponent::getHeadRoll
        )
        .add()
        .append(
            new KeyedCodec<>("RiderJumping", Codec.BOOLEAN),
            TameworkRideMountComponent::setRiderJumping,
            TameworkRideMountComponent::isRiderJumping
        )
        .add()
        .append(
            new KeyedCodec<>("RiderCrouching", Codec.BOOLEAN),
            TameworkRideMountComponent::setRiderCrouching,
            TameworkRideMountComponent::isRiderCrouching
        )
        .add()
        .append(
            new KeyedCodec<>("RiderFlying", Codec.BOOLEAN),
            TameworkRideMountComponent::setRiderFlying,
            TameworkRideMountComponent::isRiderFlying
        )
        .add()
        .append(
            new KeyedCodec<>("RiderSprinting", Codec.BOOLEAN),
            TameworkRideMountComponent::setRiderSprinting,
            TameworkRideMountComponent::isRiderSprinting
        )
        .add()
        .append(
            new KeyedCodec<>("GroundedCrouchTicks", Codec.INTEGER),
            TameworkRideMountComponent::setGroundedCrouchTicks,
            TameworkRideMountComponent::getGroundedCrouchTicks
        )
        .add()
        .append(
            new KeyedCodec<>("GroundedCrouchDismountArmed", Codec.BOOLEAN),
            TameworkRideMountComponent::setGroundedCrouchDismountArmed,
            TameworkRideMountComponent::isGroundedCrouchDismountArmed
        )
        .add()
        .append(
            new KeyedCodec<>("DismountRequested", Codec.BOOLEAN),
            TameworkRideMountComponent::setDismountRequested,
            TameworkRideMountComponent::isDismountRequested
        )
        .add()
        .append(
            new KeyedCodec<>("HasAuthoritativePose", Codec.BOOLEAN),
            TameworkRideMountComponent::setHasAuthoritativePose,
            TameworkRideMountComponent::hasAuthoritativePose
        )
        .add()
        .append(
            new KeyedCodec<>("AuthoritativeX", Codec.DOUBLE),
            TameworkRideMountComponent::setAuthoritativeX,
            TameworkRideMountComponent::getAuthoritativeX
        )
        .add()
        .append(
            new KeyedCodec<>("AuthoritativeY", Codec.DOUBLE),
            TameworkRideMountComponent::setAuthoritativeY,
            TameworkRideMountComponent::getAuthoritativeY
        )
        .add()
        .append(
            new KeyedCodec<>("AuthoritativeZ", Codec.DOUBLE),
            TameworkRideMountComponent::setAuthoritativeZ,
            TameworkRideMountComponent::getAuthoritativeZ
        )
        .add()
        .append(
            new KeyedCodec<>("AuthoritativeYaw", Codec.FLOAT),
            TameworkRideMountComponent::setAuthoritativeYaw,
            TameworkRideMountComponent::getAuthoritativeYaw
        )
        .add()
        .append(
            new KeyedCodec<>("AuthoritativePitch", Codec.FLOAT),
            TameworkRideMountComponent::setAuthoritativePitch,
            TameworkRideMountComponent::getAuthoritativePitch
        )
        .add()
        .append(
            new KeyedCodec<>("AuthoritativeRoll", Codec.FLOAT),
            TameworkRideMountComponent::setAuthoritativeRoll,
            TameworkRideMountComponent::getAuthoritativeRoll
        )
        .add()
        .build();

    private String riderUuid = "";
    private String previousState = "";
    private String previousSubState = "";
    private String previousMotionController = "";
    private String groundController = DEFAULT_GROUND_CONTROLLER;
    private String flightController = DEFAULT_FLIGHT_CONTROLLER;
    private String rideState = DEFAULT_RIDE_STATE;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private long mountStartMs;
    private long lastInputAtMs;
    private boolean hasWishMovement;
    private double wishX;
    private double wishY;
    private double wishZ;
    private boolean riderBackwardBrakeInput;
    private boolean hasBodyRotation;
    private float bodyYaw;
    private float bodyPitch;
    private float bodyRoll;
    private boolean hasHeadRotation;
    private float headYaw;
    private float headPitch;
    private float headRoll;
    private boolean riderJumping;
    private boolean riderCrouching;
    private boolean riderFlying;
    private boolean riderSprinting;
    private int groundedCrouchTicks;
    private boolean groundedCrouchDismountArmed;
    private boolean dismountRequested;
    private boolean hasAuthoritativePose;
    private double authoritativeX;
    private double authoritativeY;
    private double authoritativeZ;
    private float authoritativeYaw;
    private float authoritativePitch;
    private float authoritativeRoll;

    public TameworkRideMountComponent() {
    }

    public TameworkRideMountComponent(String riderUuid) {
        setRiderUuid(riderUuid);
    }

    public TameworkRideMountComponent(String riderUuid,
                                      String previousState,
                                      String previousSubState,
                                      String previousMotionController,
                                      String groundController,
                                      String flightController,
                                      String rideState,
                                      double anchorX,
                                      double anchorY,
                                      double anchorZ,
                                      long mountStartMs) {
        setRiderUuid(riderUuid);
        setPreviousState(previousState);
        setPreviousSubState(previousSubState);
        setPreviousMotionController(previousMotionController);
        setGroundController(groundController);
        setFlightController(flightController);
        setRideState(rideState);
        setAnchorX(anchorX);
        setAnchorY(anchorY);
        setAnchorZ(anchorZ);
        setMountStartMs(mountStartMs);
    }

    public static ComponentType<EntityStore, TameworkRideMountComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getRideMountComponentType() : null;
    }

    public String getRiderUuid() {
        return riderUuid;
    }

    public void setRiderUuid(String riderUuid) {
        this.riderUuid = sanitizeString(riderUuid);
    }

    public String getPreviousState() {
        return previousState;
    }

    public void setPreviousState(String previousState) {
        this.previousState = sanitizeString(previousState);
    }

    public String getPreviousSubState() {
        return previousSubState;
    }

    public void setPreviousSubState(String previousSubState) {
        this.previousSubState = sanitizeString(previousSubState);
    }

    public String getPreviousMotionController() {
        return previousMotionController;
    }

    public void setPreviousMotionController(String previousMotionController) {
        this.previousMotionController = sanitizeString(previousMotionController);
    }

    public String getGroundController() {
        return groundController;
    }

    public void setGroundController(String groundController) {
        this.groundController = sanitizeString(groundController, DEFAULT_GROUND_CONTROLLER);
    }

    public String getFlightController() {
        return flightController;
    }

    public void setFlightController(String flightController) {
        this.flightController = sanitizeString(flightController, DEFAULT_FLIGHT_CONTROLLER);
    }

    public String getRideState() {
        return rideState;
    }

    public void setRideState(String rideState) {
        this.rideState = sanitizeString(rideState, DEFAULT_RIDE_STATE);
    }

    public double getAnchorX() {
        return anchorX;
    }

    public void setAnchorX(double anchorX) {
        this.anchorX = anchorX;
    }

    public double getAnchorY() {
        return anchorY;
    }

    public void setAnchorY(double anchorY) {
        this.anchorY = anchorY;
    }

    public double getAnchorZ() {
        return anchorZ;
    }

    public void setAnchorZ(double anchorZ) {
        this.anchorZ = anchorZ;
    }

    public long getMountStartMs() {
        return mountStartMs;
    }

    public void setMountStartMs(long mountStartMs) {
        this.mountStartMs = Math.max(0L, mountStartMs);
    }

    public long getLastInputAtMs() {
        return lastInputAtMs;
    }

    public void setLastInputAtMs(long lastInputAtMs) {
        this.lastInputAtMs = Math.max(0L, lastInputAtMs);
    }

    public boolean hasWishMovement() {
        return hasWishMovement;
    }

    public void setHasWishMovement(boolean hasWishMovement) {
        this.hasWishMovement = hasWishMovement;
    }

    public double getWishX() {
        return wishX;
    }

    public void setWishX(double wishX) {
        this.wishX = wishX;
    }

    public double getWishY() {
        return wishY;
    }

    public void setWishY(double wishY) {
        this.wishY = wishY;
    }

    public double getWishZ() {
        return wishZ;
    }

    public void setWishZ(double wishZ) {
        this.wishZ = wishZ;
    }

    public boolean isRiderBackwardBrakeInput() {
        return riderBackwardBrakeInput;
    }

    public void setRiderBackwardBrakeInput(boolean riderBackwardBrakeInput) {
        this.riderBackwardBrakeInput = riderBackwardBrakeInput;
    }

    public boolean hasBodyRotation() {
        return hasBodyRotation;
    }

    public void setHasBodyRotation(boolean hasBodyRotation) {
        this.hasBodyRotation = hasBodyRotation;
    }

    public float getBodyYaw() {
        return bodyYaw;
    }

    public void setBodyYaw(float bodyYaw) {
        this.bodyYaw = bodyYaw;
    }

    public float getBodyPitch() {
        return bodyPitch;
    }

    public void setBodyPitch(float bodyPitch) {
        this.bodyPitch = bodyPitch;
    }

    public float getBodyRoll() {
        return bodyRoll;
    }

    public void setBodyRoll(float bodyRoll) {
        this.bodyRoll = bodyRoll;
    }

    public boolean hasHeadRotation() {
        return hasHeadRotation;
    }

    public void setHasHeadRotation(boolean hasHeadRotation) {
        this.hasHeadRotation = hasHeadRotation;
    }

    public float getHeadYaw() {
        return headYaw;
    }

    public void setHeadYaw(float headYaw) {
        this.headYaw = headYaw;
    }

    public float getHeadPitch() {
        return headPitch;
    }

    public void setHeadPitch(float headPitch) {
        this.headPitch = headPitch;
    }

    public float getHeadRoll() {
        return headRoll;
    }

    public void setHeadRoll(float headRoll) {
        this.headRoll = headRoll;
    }

    public boolean isRiderJumping() {
        return riderJumping;
    }

    public void setRiderJumping(boolean riderJumping) {
        this.riderJumping = riderJumping;
    }

    public boolean isRiderCrouching() {
        return riderCrouching;
    }

    public void setRiderCrouching(boolean riderCrouching) {
        this.riderCrouching = riderCrouching;
    }

    public boolean isRiderFlying() {
        return riderFlying;
    }

    public void setRiderFlying(boolean riderFlying) {
        this.riderFlying = riderFlying;
    }

    public boolean isRiderSprinting() {
        return riderSprinting;
    }

    public void setRiderSprinting(boolean riderSprinting) {
        this.riderSprinting = riderSprinting;
    }

    public int getGroundedCrouchTicks() {
        return groundedCrouchTicks;
    }

    public void setGroundedCrouchTicks(int groundedCrouchTicks) {
        this.groundedCrouchTicks = Math.max(0, groundedCrouchTicks);
    }

    public void incrementGroundedCrouchTicks() {
        if (groundedCrouchTicks < Integer.MAX_VALUE) {
            groundedCrouchTicks++;
        }
    }

    public void resetGroundedCrouchTicks() {
        groundedCrouchTicks = 0;
    }

    public boolean isGroundedCrouchDismountArmed() {
        return groundedCrouchDismountArmed;
    }

    public void setGroundedCrouchDismountArmed(boolean groundedCrouchDismountArmed) {
        this.groundedCrouchDismountArmed = groundedCrouchDismountArmed;
    }

    public boolean isDismountRequested() {
        return dismountRequested;
    }

    public void setDismountRequested(boolean dismountRequested) {
        this.dismountRequested = dismountRequested;
    }

    public void clearInputSnapshot() {
        clearControlInputSnapshot();
        riderJumping = false;
        riderCrouching = false;
        riderFlying = false;
        riderSprinting = false;
        groundedCrouchTicks = 0;
        groundedCrouchDismountArmed = false;
        lastInputAtMs = 0L;
        dismountRequested = false;
        clearAuthoritativePose();
    }

    public void clearControlInputSnapshot() {
        hasWishMovement = false;
        wishX = 0.0;
        wishY = 0.0;
        wishZ = 0.0;
        riderBackwardBrakeInput = false;
        hasBodyRotation = false;
        bodyYaw = 0.0f;
        bodyPitch = 0.0f;
        bodyRoll = 0.0f;
        hasHeadRotation = false;
        headYaw = 0.0f;
        headPitch = 0.0f;
        headRoll = 0.0f;
    }

    public void captureWishMovement(double wishX, double wishY, double wishZ) {
        captureWishMovement(wishX, wishY, wishZ, false);
    }

    public void captureWishMovement(double wishX, double wishY, double wishZ, boolean riderBackwardBrakeInput) {
        this.hasWishMovement = true;
        this.wishX = wishX;
        this.wishY = wishY;
        this.wishZ = wishZ;
        this.riderBackwardBrakeInput = riderBackwardBrakeInput;
    }

    public void clearWishMovement() {
        hasWishMovement = false;
        wishX = 0.0;
        wishY = 0.0;
        wishZ = 0.0;
        riderBackwardBrakeInput = false;
    }

    public void captureBodyRotation(float bodyYaw, float bodyPitch, float bodyRoll) {
        this.hasBodyRotation = true;
        this.bodyYaw = bodyYaw;
        this.bodyPitch = bodyPitch;
        this.bodyRoll = bodyRoll;
    }

    public void captureHeadRotation(float headYaw, float headPitch, float headRoll) {
        this.hasHeadRotation = true;
        this.headYaw = headYaw;
        this.headPitch = headPitch;
        this.headRoll = headRoll;
    }

    public void captureRiderMovementStates(MovementStates states) {
        if (states == null) {
            riderJumping = false;
            riderCrouching = false;
            riderFlying = false;
            riderSprinting = false;
            return;
        }
        riderJumping = states.jumping || states.swimJumping;
        riderCrouching = states.crouching || states.forcedCrouching;
        riderFlying = states.flying;
        riderSprinting = states.sprinting || states.running;
    }

    public boolean hasAuthoritativePose() {
        return hasAuthoritativePose;
    }

    public void setHasAuthoritativePose(boolean hasAuthoritativePose) {
        this.hasAuthoritativePose = hasAuthoritativePose;
    }

    public double getAuthoritativeX() {
        return authoritativeX;
    }

    public void setAuthoritativeX(double authoritativeX) {
        this.authoritativeX = authoritativeX;
    }

    public double getAuthoritativeY() {
        return authoritativeY;
    }

    public void setAuthoritativeY(double authoritativeY) {
        this.authoritativeY = authoritativeY;
    }

    public double getAuthoritativeZ() {
        return authoritativeZ;
    }

    public void setAuthoritativeZ(double authoritativeZ) {
        this.authoritativeZ = authoritativeZ;
    }

    public float getAuthoritativeYaw() {
        return authoritativeYaw;
    }

    public void setAuthoritativeYaw(float authoritativeYaw) {
        this.authoritativeYaw = authoritativeYaw;
    }

    public float getAuthoritativePitch() {
        return authoritativePitch;
    }

    public void setAuthoritativePitch(float authoritativePitch) {
        this.authoritativePitch = authoritativePitch;
    }

    public float getAuthoritativeRoll() {
        return authoritativeRoll;
    }

    public void setAuthoritativeRoll(float authoritativeRoll) {
        this.authoritativeRoll = authoritativeRoll;
    }

    public void captureAuthoritativePose(double x,
                                         double y,
                                         double z,
                                         float yaw,
                                         float pitch,
                                         float roll) {
        hasAuthoritativePose = true;
        authoritativeX = x;
        authoritativeY = y;
        authoritativeZ = z;
        authoritativeYaw = yaw;
        authoritativePitch = pitch;
        authoritativeRoll = roll;
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

    @Override
    public TameworkRideMountComponent clone() {
        TameworkRideMountComponent clone = new TameworkRideMountComponent(
                riderUuid,
                previousState,
                previousSubState,
                previousMotionController,
                groundController,
                flightController,
                rideState,
                anchorX,
                anchorY,
                anchorZ,
                mountStartMs
        );
        clone.lastInputAtMs = lastInputAtMs;
        clone.hasWishMovement = hasWishMovement;
        clone.wishX = wishX;
        clone.wishY = wishY;
        clone.wishZ = wishZ;
        clone.riderBackwardBrakeInput = riderBackwardBrakeInput;
        clone.hasBodyRotation = hasBodyRotation;
        clone.bodyYaw = bodyYaw;
        clone.bodyPitch = bodyPitch;
        clone.bodyRoll = bodyRoll;
        clone.hasHeadRotation = hasHeadRotation;
        clone.headYaw = headYaw;
        clone.headPitch = headPitch;
        clone.headRoll = headRoll;
        clone.riderJumping = riderJumping;
        clone.riderCrouching = riderCrouching;
        clone.riderFlying = riderFlying;
        clone.riderSprinting = riderSprinting;
        clone.groundedCrouchTicks = groundedCrouchTicks;
        clone.groundedCrouchDismountArmed = groundedCrouchDismountArmed;
        clone.dismountRequested = dismountRequested;
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
        return value == null || value.isBlank() ? "" : value;
    }

    private static String sanitizeString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
