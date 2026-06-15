package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.simple.BooleanCodec;
import com.hypixel.hytale.codec.codecs.simple.DoubleCodec;
import com.hypixel.hytale.codec.codecs.simple.LongCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Stores high-level Tamework control state for flying companions that need help settling on the ground.
 */
public final class TameworkFlyingCompanionComponent implements Component<EntityStore> {
    public static final String MODE_TAG = "Mode";
    public static final String PHASE_TAG = "Phase";
    public static final String LANDING_STATE_TAG = "LandingState";
    public static final String GROUNDED_STATE_TAG = "GroundedState";
    public static final String DESCEND_STEP_TAG = "DescendStep";
    public static final String REISSUE_DELAY_MS_TAG = "ReissueDelayMs";
    public static final String GROUNDED_STABLE_TICKS_TAG = "GroundedStableTicks";
    public static final String VERTICAL_MOVEMENT_EPSILON_TAG = "VerticalMovementEpsilon";
    public static final String LAST_OBSERVED_Y_TAG = "LastObservedY";
    public static final String STABLE_TICK_COUNT_TAG = "StableTickCount";
    public static final String NEXT_COMMAND_AT_MS_TAG = "NextCommandAtMs";
    public static final String LANDING_TARGET_SLOT_INDEX_TAG = "LandingTargetSlotIndex";
    public static final String HAS_LANDING_TARGET_POSITION_TAG = "HasLandingTargetPosition";
    public static final String LANDING_TARGET_X_TAG = "LandingTargetX";
    public static final String LANDING_TARGET_Y_TAG = "LandingTargetY";
    public static final String LANDING_TARGET_Z_TAG = "LandingTargetZ";

    public static final String MODE_FOLLOW = "Follow";
    public static final String MODE_HOLD = "Hold";
    public static final String MODE_SETTLED = "Settled";
    public static final String PHASE_FOLLOWING = "Following";
    public static final String PHASE_LANDING = "Landing";
    public static final String PHASE_GROUNDED = "Grounded";

    public static final BuilderCodec<TameworkFlyingCompanionComponent> CODEC = BuilderCodec.builder(
            TameworkFlyingCompanionComponent.class,
            TameworkFlyingCompanionComponent::new
    )
        .append(
            new KeyedCodec<>(MODE_TAG, new StringCodec()),
            TameworkFlyingCompanionComponent::setMode,
            TameworkFlyingCompanionComponent::getMode
        )
        .add()
        .append(
            new KeyedCodec<>(PHASE_TAG, new StringCodec()),
            TameworkFlyingCompanionComponent::setPhase,
            TameworkFlyingCompanionComponent::getPhase
        )
        .add()
        .append(
            new KeyedCodec<>(LANDING_STATE_TAG, new StringCodec()),
            TameworkFlyingCompanionComponent::setLandingState,
            TameworkFlyingCompanionComponent::getLandingState
        )
        .add()
        .append(
            new KeyedCodec<>(GROUNDED_STATE_TAG, new StringCodec()),
            TameworkFlyingCompanionComponent::setGroundedState,
            TameworkFlyingCompanionComponent::getGroundedState
        )
        .add()
        .append(
            new KeyedCodec<>(DESCEND_STEP_TAG, new DoubleCodec()),
            TameworkFlyingCompanionComponent::setDescendStep,
            TameworkFlyingCompanionComponent::getDescendStep
        )
        .add()
        .append(
            new KeyedCodec<>(REISSUE_DELAY_MS_TAG, new LongCodec()),
            TameworkFlyingCompanionComponent::setReissueDelayMs,
            TameworkFlyingCompanionComponent::getReissueDelayMs
        )
        .add()
        .append(
            new KeyedCodec<>(GROUNDED_STABLE_TICKS_TAG, new LongCodec()),
            TameworkFlyingCompanionComponent::setGroundedStableTicks,
            TameworkFlyingCompanionComponent::getGroundedStableTicks
        )
        .add()
        .append(
            new KeyedCodec<>(VERTICAL_MOVEMENT_EPSILON_TAG, new DoubleCodec()),
            TameworkFlyingCompanionComponent::setVerticalMovementEpsilon,
            TameworkFlyingCompanionComponent::getVerticalMovementEpsilon
        )
        .add()
        .append(
            new KeyedCodec<>(LAST_OBSERVED_Y_TAG, new DoubleCodec()),
            TameworkFlyingCompanionComponent::setLastObservedY,
            TameworkFlyingCompanionComponent::getLastObservedY
        )
        .add()
        .append(
            new KeyedCodec<>(STABLE_TICK_COUNT_TAG, new LongCodec()),
            TameworkFlyingCompanionComponent::setStableTickCount,
            TameworkFlyingCompanionComponent::getStableTickCount
        )
        .add()
        .append(
            new KeyedCodec<>(NEXT_COMMAND_AT_MS_TAG, new LongCodec()),
            TameworkFlyingCompanionComponent::setNextCommandAtMs,
            TameworkFlyingCompanionComponent::getNextCommandAtMs
        )
        .add()
        .append(
            new KeyedCodec<>(LANDING_TARGET_SLOT_INDEX_TAG, new LongCodec()),
            TameworkFlyingCompanionComponent::setLandingTargetSlotIndex,
            TameworkFlyingCompanionComponent::getLandingTargetSlotIndex
        )
        .add()
        .append(
            new KeyedCodec<>(HAS_LANDING_TARGET_POSITION_TAG, new BooleanCodec()),
            TameworkFlyingCompanionComponent::setHasLandingTargetPosition,
            TameworkFlyingCompanionComponent::hasLandingTargetPosition
        )
        .add()
        .append(
            new KeyedCodec<>(LANDING_TARGET_X_TAG, new DoubleCodec()),
            TameworkFlyingCompanionComponent::setLandingTargetX,
            TameworkFlyingCompanionComponent::getLandingTargetX
        )
        .add()
        .append(
            new KeyedCodec<>(LANDING_TARGET_Y_TAG, new DoubleCodec()),
            TameworkFlyingCompanionComponent::setLandingTargetY,
            TameworkFlyingCompanionComponent::getLandingTargetY
        )
        .add()
        .append(
            new KeyedCodec<>(LANDING_TARGET_Z_TAG, new DoubleCodec()),
            TameworkFlyingCompanionComponent::setLandingTargetZ,
            TameworkFlyingCompanionComponent::getLandingTargetZ
        )
        .add()
        .build();

    private String mode = MODE_FOLLOW;
    private String phase = PHASE_FOLLOWING;
    private String landingState = "Hold";
    private String groundedState = "HoldGrounded";
    private double descendStep = 4.0;
    private long reissueDelayMs = 300L;
    private long groundedStableTicks = 3L;
    private double verticalMovementEpsilon = 0.15;
    private double lastObservedY;
    private long stableTickCount;
    private long nextCommandAtMs;
    private long landingTargetSlotIndex = -1L;
    private boolean hasLandingTargetPosition;
    private double landingTargetX;
    private double landingTargetY;
    private double landingTargetZ;

    public static ComponentType<EntityStore, TameworkFlyingCompanionComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getFlyingCompanionComponentType() : null;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = normalizeMode(mode);
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = normalizePhase(phase);
    }

    public String getLandingState() {
        return landingState;
    }

    public void setLandingState(String landingState) {
        this.landingState = landingState == null ? "" : landingState;
    }

    public String getGroundedState() {
        return groundedState;
    }

    public void setGroundedState(String groundedState) {
        this.groundedState = groundedState == null ? "" : groundedState;
    }

    public double getDescendStep() {
        return descendStep;
    }

    public void setDescendStep(double descendStep) {
        this.descendStep = descendStep > 0.05 ? descendStep : 4.0;
    }

    public long getReissueDelayMs() {
        return reissueDelayMs;
    }

    public void setReissueDelayMs(long reissueDelayMs) {
        this.reissueDelayMs = reissueDelayMs > 25L ? reissueDelayMs : 300L;
    }

    public long getGroundedStableTicks() {
        return groundedStableTicks;
    }

    public void setGroundedStableTicks(long groundedStableTicks) {
        this.groundedStableTicks = groundedStableTicks > 0 ? groundedStableTicks : 3L;
    }

    public double getVerticalMovementEpsilon() {
        return verticalMovementEpsilon;
    }

    public void setVerticalMovementEpsilon(double verticalMovementEpsilon) {
        this.verticalMovementEpsilon = verticalMovementEpsilon >= 0.0 ? verticalMovementEpsilon : 0.15;
    }

    public double getLastObservedY() {
        return lastObservedY;
    }

    public void setLastObservedY(double lastObservedY) {
        this.lastObservedY = lastObservedY;
    }

    public long getStableTickCount() {
        return stableTickCount;
    }

    public void setStableTickCount(long stableTickCount) {
        this.stableTickCount = Math.max(0L, stableTickCount);
    }

    public long getNextCommandAtMs() {
        return nextCommandAtMs;
    }

    public void setNextCommandAtMs(long nextCommandAtMs) {
        this.nextCommandAtMs = Math.max(0L, nextCommandAtMs);
    }

    public long getLandingTargetSlotIndex() {
        return landingTargetSlotIndex;
    }

    public void setLandingTargetSlotIndex(long landingTargetSlotIndex) {
        this.landingTargetSlotIndex = landingTargetSlotIndex >= 0L ? landingTargetSlotIndex : -1L;
    }

    public boolean hasLandingTargetSlot() {
        return landingTargetSlotIndex >= 0L;
    }

    public boolean hasLandingTargetPosition() {
        return hasLandingTargetPosition;
    }

    public void setHasLandingTargetPosition(boolean hasLandingTargetPosition) {
        this.hasLandingTargetPosition = hasLandingTargetPosition;
    }

    public double getLandingTargetX() {
        return landingTargetX;
    }

    public void setLandingTargetX(double landingTargetX) {
        this.landingTargetX = landingTargetX;
    }

    public double getLandingTargetY() {
        return landingTargetY;
    }

    public void setLandingTargetY(double landingTargetY) {
        this.landingTargetY = landingTargetY;
    }

    public double getLandingTargetZ() {
        return landingTargetZ;
    }

    public void setLandingTargetZ(double landingTargetZ) {
        this.landingTargetZ = landingTargetZ;
    }

    @Nullable
    public Vector3d getLandingTargetPosition() {
        if (!hasLandingTargetPosition) {
            return null;
        }
        return new Vector3d(landingTargetX, landingTargetY, landingTargetZ);
    }

    public void setLandingTargetPosition(@Nullable Vector3d landingTargetPosition) {
        if (landingTargetPosition == null
                || !Double.isFinite(landingTargetPosition.x)
                || !Double.isFinite(landingTargetPosition.y)
                || !Double.isFinite(landingTargetPosition.z)) {
            hasLandingTargetPosition = false;
            landingTargetX = 0.0;
            landingTargetY = 0.0;
            landingTargetZ = 0.0;
            return;
        }
        hasLandingTargetPosition = true;
        landingTargetX = landingTargetPosition.x;
        landingTargetY = landingTargetPosition.y;
        landingTargetZ = landingTargetPosition.z;
    }

    public boolean isHoldMode() {
        return MODE_HOLD.equals(mode);
    }

    public boolean isSettledMode() {
        return MODE_SETTLED.equals(mode);
    }

    public boolean isGroundedPhase() {
        return PHASE_GROUNDED.equals(phase);
    }

    public void configure(String mode,
                          String landingState,
                          String groundedState,
                          double descendStep,
                          long reissueDelayMs,
                          long groundedStableTicks,
                          double verticalMovementEpsilon,
                          long landingTargetSlotIndex,
                          @Nullable Vector3d landingTargetPosition) {
        setMode(mode);
        setLandingState(landingState);
        setGroundedState(groundedState);
        setDescendStep(descendStep);
        setReissueDelayMs(reissueDelayMs);
        setGroundedStableTicks(groundedStableTicks);
        setVerticalMovementEpsilon(verticalMovementEpsilon);
        setLandingTargetSlotIndex(landingTargetSlotIndex);
        setLandingTargetPosition(landingTargetPosition);
    }

    public void enterFollowingPhase() {
        setPhase(PHASE_FOLLOWING);
        stableTickCount = 0;
        nextCommandAtMs = 0L;
        landingTargetSlotIndex = -1L;
        setLandingTargetPosition(null);
    }

    public void enterLandingPhase(double currentY) {
        setPhase(PHASE_LANDING);
        lastObservedY = currentY;
        stableTickCount = 0;
        nextCommandAtMs = 0L;
    }

    public void enterGroundedPhase(double currentY) {
        setPhase(PHASE_GROUNDED);
        lastObservedY = currentY;
        stableTickCount = 0;
        nextCommandAtMs = 0L;
    }

    @Override
    public TameworkFlyingCompanionComponent clone() {
        TameworkFlyingCompanionComponent clone = new TameworkFlyingCompanionComponent();
        clone.mode = mode;
        clone.phase = phase;
        clone.landingState = landingState;
        clone.groundedState = groundedState;
        clone.descendStep = descendStep;
        clone.reissueDelayMs = reissueDelayMs;
        clone.groundedStableTicks = groundedStableTicks;
        clone.verticalMovementEpsilon = verticalMovementEpsilon;
        clone.lastObservedY = lastObservedY;
        clone.stableTickCount = stableTickCount;
        clone.nextCommandAtMs = nextCommandAtMs;
        clone.landingTargetSlotIndex = landingTargetSlotIndex;
        clone.hasLandingTargetPosition = hasLandingTargetPosition;
        clone.landingTargetX = landingTargetX;
        clone.landingTargetY = landingTargetY;
        clone.landingTargetZ = landingTargetZ;
        return clone;
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_FOLLOW;
        }
        if (MODE_SETTLED.equalsIgnoreCase(mode)) {
            return MODE_SETTLED;
        }
        return MODE_HOLD.equalsIgnoreCase(mode) ? MODE_HOLD : MODE_FOLLOW;
    }

    private static String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return PHASE_FOLLOWING;
        }
        if (PHASE_GROUNDED.equalsIgnoreCase(phase)) {
            return PHASE_GROUNDED;
        }
        if (PHASE_LANDING.equalsIgnoreCase(phase)) {
            return PHASE_LANDING;
        }
        return PHASE_FOLLOWING;
    }
}
