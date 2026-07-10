package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.SavedMovementStates;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesSystems;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.modules.entity.system.ModelSystems;
import com.hypixel.hytale.server.core.modules.entity.system.TransformSystems;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.physics.systems.IVelocityModifyingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Applies avatar-flight controller velocity to transformed player entities.
 */
public final class AvatarFlightMovementSystem
        extends EntityTickingSystem<EntityStore>
        implements IVelocityModifyingSystem {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, AvatarFlightInputComponent> inputType;
    private final ComponentType<EntityStore, Velocity> velocityType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesType;
    private final ComponentType<EntityStore, HeadRotation> headRotationType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final Query<EntityStore> query;
    private final AvatarFlightDebugLogService debugLogService = new AvatarFlightDebugLogService();
    private final AvatarFlightLaunchVfxService launchVfxService = new AvatarFlightLaunchVfxService();
    private final AvatarFlightLaunchAudioService launchAudioService = new AvatarFlightLaunchAudioService();
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, PlayerSystems.ProcessPlayerInput.class),
            new SystemDependency<>(Order.AFTER, MovementStatesSystems.TickingSystem.class),
            new SystemDependency<>(Order.BEFORE, ModelSystems.AnimationEntityTrackerUpdate.class),
            new SystemDependency<>(Order.BEFORE, TransformSystems.EntityTrackerUpdate.class)
    );

    public AvatarFlightMovementSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
            @Nonnull ComponentType<EntityStore, AvatarFlightInputComponent> inputType,
            @Nullable ComponentType<EntityStore, Velocity> velocityType,
            @Nonnull ComponentType<EntityStore, MovementStatesComponent> movementStatesType,
            @Nonnull ComponentType<EntityStore, HeadRotation> headRotationType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType) {
        this.flightType = flightType;
        this.inputType = inputType;
        this.velocityType = velocityType == null ? Velocity.getComponentType() : velocityType;
        this.movementStatesType = movementStatesType;
        this.headRotationType = headRotationType;
        this.transformType = transformType;
        this.query = Query.and(flightType, this.velocityType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        AvatarFlightComponent flight = archetypeChunk.getComponent(index, flightType);
        Velocity velocity = archetypeChunk.getComponent(index, velocityType);
        if (ref == null || flight == null || velocity == null) {
            return;
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
        AvatarFlightInputComponent input = commandBuffer.getComponent(ref, inputType);
        MovementStates movementStates = resolveMovementStates(ref, commandBuffer);
        AvatarFlightController.Input rawControllerInput =
                toControllerInput(input, movementStates, flight, ref, commandBuffer, config);
        if (input != null) {
            commandBuffer.putComponent(ref, inputType, input);
        }
        long now = System.currentTimeMillis();
        rechargeVigour(flight, config, rawControllerInput, now);
        AvatarFlightController.Input controllerInput = authorizeVigour(rawControllerInput, flight, config, now);
        AvatarFlightController.State state = AvatarFlightController.State.from(flight);
        AvatarFlightController.Output output = AvatarFlightController.update(
                state,
                controllerInput,
                config,
                Math.max(0.0, dt),
                now
        );
        spendAppliedVigour(flight, config, output, now);
        TransformComponent transform = commandBuffer.getComponent(ref, transformType);
        launchVfxService.tick(
                flight,
                input,
                controllerInput,
                output,
                config,
                transform,
                now,
                ref,
                commandBuffer
        );
        launchAudioService.tick(
                flight,
                input,
                controllerInput,
                output,
                config,
                transform,
                now,
                commandBuffer
        );
        flight.setMode(output.mode());
        flight.setVelocity(output.velocityX(), output.velocityY(), output.velocityZ());
        flight.setNextJumpAtMs(output.nextJumpAtMs());
        flight.setNextBoostAtMs(output.nextBoostAtMs());
        flight.setNextLaunchAtMs(output.nextLaunchAtMs());
        flight.setDiveLoad(output.diveLoad());
        flight.setClimbLoad(output.climbLoad());
        flight.setHudPitchRadians(output.visualPitchRadians());
        flight.setHudTargetSpeedRatio(output.hudTargetSpeedRatio());
        boolean applyingVelocity = output.applyVelocity();
        boolean hasFlightVisualOverrides = hasFlightVisualOverrides(flight);
        boolean suppressingOverlays =
                shouldSuppressPlayerOverlayAnimations(config, applyingVelocity, hasFlightVisualOverrides);
        syncOwnerClientFlyingState(ref, commandBuffer, flight, applyingVelocity);
        suppressPlayerOverlayAnimations(ref, commandBuffer, flight, config, suppressingOverlays);
        if (applyingVelocity) {
            applyVisualPose(ref, commandBuffer, controllerInput, output);
            applyPoseAnimations(ref, commandBuffer, flight, config, output);
            velocity.addInstruction(
                    new Vector3d(output.velocityX(), output.velocityY(), output.velocityZ()),
                    null,
                    ChangeVelocityType.Set
            );
            applyFlightMovementState(ref, commandBuffer, output);
            applyMovementAnimation(ref, commandBuffer, flight, config, output);
        } else if (hasFlightVisualOverrides) {
            clearFlightVisualOverrides(ref, commandBuffer, flight, config, controllerInput);
        }
        commandBuffer.putComponent(ref, flightType, flight);
        debugLogService.maybeLogControllerTick(
                config,
                flight,
                ref,
                controllerInput,
                output,
                input,
                movementStates,
                applyingVelocity,
                hasFlightVisualOverrides,
                suppressingOverlays
        );
    }

    private static void rechargeVigour(@Nonnull AvatarFlightComponent flight,
                                       @Nonnull TwAvatarFlightConfig config,
                                       @Nonnull AvatarFlightController.Input input,
                                       long now) {
        double horizontalSpeed = AvatarFlightSpeedMetrics.horizontalSpeed(
                flight.getVelocityX(),
                flight.getVelocityY(),
                flight.getVelocityZ()
        );
        AvatarFlightVigourService.Result recharge = AvatarFlightVigourService.recharge(
                new AvatarFlightVigourService.State(
                        initialVigourCharges(flight, config),
                        flight.getLastVigourUpdateAtMs(),
                        flight.getVigourRechargeBlockedUntilMs()
                ),
                config,
                input.onGround(),
                horizontalSpeed,
                now
        );
        applyVigourState(flight, recharge.state());
        flight.setVigourRechargeMode(recharge.mode().name());
    }

    @Nonnull
    private static AvatarFlightController.Input authorizeVigour(@Nonnull AvatarFlightController.Input input,
                                                               @Nonnull AvatarFlightComponent flight,
                                                               @Nonnull TwAvatarFlightConfig config,
                                                               long now) {
        if (!config.getVigour().isEnabled()) {
            return withVigourAuthorization(input, true, true, true);
        }
        double flapCost = config.getVigour().getUpwardFlapCost();
        double boostCost = config.getVigour().getForwardBoostCost();
        double launchCost = AvatarFlightLaunchCurve.cost(config.getLaunch(), input.launchHoldMs());
        AvatarFlightVigourService.State state = new AvatarFlightVigourService.State(
                flight.getVigourCharges(),
                flight.getLastVigourUpdateAtMs(),
                flight.getVigourRechargeBlockedUntilMs()
        );
        boolean flapAllowed = AvatarFlightVigourService.canSpend(
                state,
                config,
                flapCost
        );
        boolean boostAllowed = AvatarFlightVigourService.canSpend(
                state,
                config,
                boostCost
        );
        boolean launchAllowed = AvatarFlightVigourService.canSpend(
                state,
                config,
                launchCost
        );
        if (flapAllowed
                && flapEligibleThisTick(input, flight, now)
                && boostEligibleThisTick(input, flight, now)
                && !AvatarFlightVigourService.canSpend(state, config, combinedCost(flapCost, boostCost))) {
            boostAllowed = false;
        }
        return withVigourAuthorization(input, flapAllowed, boostAllowed, launchAllowed);
    }

    private static void spendAppliedVigour(@Nonnull AvatarFlightComponent flight,
                                           @Nonnull TwAvatarFlightConfig config,
                                           @Nonnull AvatarFlightController.Output output,
                                           long now) {
        if (!config.getVigour().isEnabled()) {
            return;
        }
        AvatarFlightVigourService.State state = new AvatarFlightVigourService.State(
                flight.getVigourCharges(),
                flight.getLastVigourUpdateAtMs(),
                flight.getVigourRechargeBlockedUntilMs()
        );
        boolean spent = false;
        if (output.jumpApplied()) {
            state = AvatarFlightVigourService.spend(
                    state,
                    config,
                    config.getVigour().getUpwardFlapCost(),
                    now
            );
            spent = true;
        }
        if (output.boostApplied()) {
            state = AvatarFlightVigourService.spend(
                    state,
                    config,
                    config.getVigour().getForwardBoostCost(),
                    now
            );
            spent = true;
        }
        if (output.launchApplied()) {
            state = AvatarFlightVigourService.spend(
                    state,
                    config,
                    output.launchCost(),
                    now
            );
            spent = true;
        }
        if (!spent) {
            return;
        }
        applyVigourState(flight, state);
        flight.setVigourRechargeMode(AvatarFlightVigourService.RechargeMode.DELAYED.name());
    }

    private static boolean flapEligibleThisTick(@Nonnull AvatarFlightController.Input input,
                                                @Nonnull AvatarFlightComponent flight,
                                                long now) {
        return (input.jump() || input.verticalAxis() > 0.0)
                && cooldownReady(flight.getNextJumpAtMs(), now);
    }

    private static boolean boostEligibleThisTick(@Nonnull AvatarFlightController.Input input,
                                                 @Nonnull AvatarFlightComponent flight,
                                                 long now) {
        return !input.airbrake()
                && input.sprint()
                && cooldownReady(flight.getNextBoostAtMs(), now);
    }

    private static boolean cooldownReady(long nextAtMs, long now) {
        return nextAtMs == 0L || now >= nextAtMs;
    }

    private static double combinedCost(double firstCost, double secondCost) {
        return paidCost(firstCost) + paidCost(secondCost);
    }

    private static double paidCost(double cost) {
        return Double.isNaN(cost) || cost <= 0.0 ? 0.0 : cost;
    }

    @Nonnull
    private static AvatarFlightController.Input withVigourAuthorization(@Nonnull AvatarFlightController.Input input,
                                                                       boolean flapAllowed,
                                                                       boolean boostAllowed,
                                                                       boolean launchAllowed) {
        return new AvatarFlightController.Input(
                input.forwardAxis(),
                input.strafeAxis(),
                input.verticalAxis(),
                input.jump(),
                input.crouch(),
                input.sprint(),
                input.airbrake(),
                input.onGround(),
                input.yawRadians(),
                input.pitchRadians(),
                flapAllowed,
                boostAllowed,
                launchAllowed,
                input.launchHoldMs()
        );
    }

    private static void applyVigourState(@Nonnull AvatarFlightComponent flight,
                                         @Nonnull AvatarFlightVigourService.State state) {
        flight.setVigourCharges(state.charges());
        flight.setLastVigourUpdateAtMs(state.lastUpdateAtMs());
        flight.setVigourRechargeBlockedUntilMs(state.rechargeBlockedUntilMs());
    }

    private static double initialVigourCharges(@Nonnull AvatarFlightComponent flight,
                                               @Nonnull TwAvatarFlightConfig config) {
        double maxCharges = maxVigourCharges(config);
        if (!config.getVigour().isEnabled()) {
            return maxCharges;
        }
        double charges = flight.getVigourCharges();
        if (flight.getLastVigourUpdateAtMs() == 0L && charges <= 0.0) {
            return maxCharges;
        }
        return charges;
    }

    private static double maxVigourCharges(@Nonnull TwAvatarFlightConfig config) {
        double maxCharges = config.getVigour().getMaxCharges();
        return Double.isFinite(maxCharges) && maxCharges > 0.0 ? maxCharges : 0.0;
    }

    private void syncOwnerClientFlyingState(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                            @Nonnull AvatarFlightComponent flight,
                                            boolean desiredFlying) {
        if (flight.isClientFlyingSynced() != desiredFlying) {
            MovementStatesComponent component = commandBuffer.getComponent(ref, movementStatesType);
            if (component == null || component.getMovementStates() == null) {
                return;
            }
            Player.applyMovementStates(
                    ref,
                    new SavedMovementStates(desiredFlying),
                    component.getMovementStates(),
                    commandBuffer
            );
            flight.setClientFlyingSynced(desiredFlying);
        }
    }

    private void applyFlightMovementState(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                          @Nonnull AvatarFlightController.Output output) {
        MovementStatesComponent component = commandBuffer.getComponent(ref, movementStatesType);
        if (component == null) {
            return;
        }
        MovementStates states = component.getMovementStates();
        states = states == null ? new MovementStates() : new MovementStates(states);
        states.idle = output.horizontalIdle();
        states.horizontalIdle = output.horizontalIdle();
        states.flying = true;
        states.sprinting = false;
        states.walking = false;
        states.running = false;
        states.onGround = false;
        states.jumping = false;
        states.crouching = false;
        states.falling = false;
        states.fallingFar = false;
        component.setMovementStates(states);
        commandBuffer.putComponent(ref, movementStatesType, component);
    }

    private void applyMovementAnimation(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull AvatarFlightComponent flight,
                                        @Nonnull TwAvatarFlightConfig config,
                                        @Nonnull AvatarFlightController.Output output) {
        String animationId = config.getAnimation().animationFor(output.horizontalIdle(), output.fastFlight());
        long now = System.currentTimeMillis();
        if (animationId.equals(flight.getMovementAnimationId()) && now < flight.getNextMovementAnimationAtMs()) {
            return;
        }
        AnimationUtils.playAnimation(ref, AnimationSlot.Movement, animationId, true, commandBuffer);
        flight.setMovementAnimationId(animationId);
        flight.setNextMovementAnimationAtMs(now + config.getAnimation().getResendIntervalMs());
    }

    private void clearMovementAnimation(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull AvatarFlightComponent flight) {
        if (flight.getMovementAnimationId().isBlank()) {
            return;
        }
        AnimationUtils.stopAnimation(ref, AnimationSlot.Movement, true, commandBuffer);
        flight.setMovementAnimationId("");
        flight.setNextMovementAnimationAtMs(0L);
    }

    private boolean hasFlightVisualOverrides(@Nonnull AvatarFlightComponent flight) {
        return flight.isClientFlyingSynced()
                || !flight.getMovementAnimationId().isBlank()
                || !flight.getPitchPoseAnimationId().isBlank()
                || !flight.getRollPoseAnimationId().isBlank();
    }

    private void clearFlightVisualOverrides(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                            @Nonnull AvatarFlightComponent flight,
                                            @Nonnull TwAvatarFlightConfig config,
                                            @Nonnull AvatarFlightController.Input controllerInput) {
        clearFlightMovementState(ref, commandBuffer, controllerInput);
        clearMovementAnimation(ref, commandBuffer, flight);
        clearPoseAnimations(ref, commandBuffer, flight, config);
        resetVisualPose(ref, commandBuffer);
    }

    private void clearFlightMovementState(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                          @Nonnull AvatarFlightController.Input input) {
        MovementStatesComponent component = commandBuffer.getComponent(ref, movementStatesType);
        if (component == null) {
            return;
        }
        MovementStates states = component.getMovementStates();
        states = states == null ? new MovementStates() : new MovementStates(states);
        states.flying = false;
        states.sprinting = false;
        states.running = false;
        states.walking = false;
        states.jumping = false;
        states.crouching = false;
        states.falling = false;
        states.fallingFar = false;
        states.climbing = false;
        states.mantling = false;
        states.sliding = false;
        states.gliding = false;
        states.idle = true;
        states.horizontalIdle = true;
        states.onGround = input.onGround();
        component.setMovementStates(states);
        commandBuffer.putComponent(ref, movementStatesType, component);
    }

    private void clearPoseAnimations(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull AvatarFlightComponent flight,
                                     @Nonnull TwAvatarFlightConfig config) {
        TwAvatarFlightConfig.AnimationSettings animation = config.getAnimation();
        AnimationSlot pitchSlot = resolveAnimationSlot(animation.getPitchPoseSlot(), AnimationSlot.Status);
        AnimationSlot rollSlot = resolveAnimationSlot(animation.getRollPoseSlot(), AnimationSlot.Emote);
        clearPoseAnimation(ref, commandBuffer, flight, true, pitchSlot);
        clearPoseAnimation(ref, commandBuffer, flight, false, rollSlot);
    }

    private static boolean shouldSuppressPlayerOverlayAnimations(@Nonnull TwAvatarFlightConfig config,
                                                                 boolean applyingVelocity,
                                                                 boolean hasFlightVisualOverrides) {
        if (!applyingVelocity && !hasFlightVisualOverrides) {
            return false;
        }
        TwAvatarFlightConfig.AnimationSettings animation = config.getAnimation();
        return animation.isSuppressNonMovementAnimations();
    }

    private void suppressPlayerOverlayAnimations(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                 @Nonnull AvatarFlightComponent flight,
                                                 @Nonnull TwAvatarFlightConfig config,
                                                 boolean suppressingOverlays) {
        if (!suppressingOverlays) {
            return;
        }
        TwAvatarFlightConfig.AnimationSettings animation = config.getAnimation();
        long now = System.currentTimeMillis();
        if (now < flight.getNextSuppressedAnimationAtMs()) {
            return;
        }
        AnimationSlot pitchSlot = animation.isPoseAnimationsEnabled()
                ? resolveAnimationSlot(animation.getPitchPoseSlot(), AnimationSlot.Status) : null;
        AnimationSlot rollSlot = animation.isPoseAnimationsEnabled()
                ? resolveAnimationSlot(animation.getRollPoseSlot(), AnimationSlot.Emote) : null;
        if (animation.isSuppressActionAnimation() && !isPoseSlot(AnimationSlot.Action, pitchSlot, rollSlot)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, commandBuffer);
        }
        if (animation.isSuppressStatusAnimation() && !isPoseSlot(AnimationSlot.Status, pitchSlot, rollSlot)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, commandBuffer);
        }
        if (animation.isSuppressEmoteAnimation() && !isPoseSlot(AnimationSlot.Emote, pitchSlot, rollSlot)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Emote, true, commandBuffer);
        }
        if (animation.isSuppressFaceAnimation() && !isPoseSlot(AnimationSlot.Face, pitchSlot, rollSlot)) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Face, true, commandBuffer);
        }
        flight.setNextSuppressedAnimationAtMs(now + animation.getSuppressionIntervalMs());
    }

    private void applyPoseAnimations(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull AvatarFlightComponent flight,
                                     @Nonnull TwAvatarFlightConfig config,
                                     @Nonnull AvatarFlightController.Output output) {
        TwAvatarFlightConfig.AnimationSettings animation = config.getAnimation();
        AnimationSlot pitchSlot = resolveAnimationSlot(animation.getPitchPoseSlot(), AnimationSlot.Status);
        AnimationSlot rollSlot = resolveAnimationSlot(animation.getRollPoseSlot(), AnimationSlot.Emote);
        if (!animation.isPoseAnimationsEnabled()) {
            clearPoseAnimation(ref, commandBuffer, flight, true, pitchSlot);
            clearPoseAnimation(ref, commandBuffer, flight, false, rollSlot);
            return;
        }
        long now = System.currentTimeMillis();
        if (pitchSlot == rollSlot) {
            drivePoseAnimation(ref, commandBuffer, flight, true, pitchSlot,
                    AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor(
                            animation,
                            Math.toDegrees(output.visualPitchRadians()),
                            Math.toDegrees(output.visualRollRadians())
                    ),
                    now, animation.getPoseResendIntervalMs());
            flight.setRollPoseAnimationId("");
            flight.setNextRollPoseAnimationAtMs(0L);
            return;
        }
        drivePoseAnimation(ref, commandBuffer, flight, true, pitchSlot,
                AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor(
                        animation,
                        Math.toDegrees(output.visualPitchRadians())),
                now, animation.getPoseResendIntervalMs());
        drivePoseAnimation(ref, commandBuffer, flight, false, rollSlot,
                AvatarFlightPoseAnimationCatalog.rollPoseAnimationFor(
                        animation,
                        Math.toDegrees(output.visualRollRadians())),
                now, animation.getPoseResendIntervalMs());
    }

    private void drivePoseAnimation(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull AvatarFlightComponent flight,
                                    boolean pitch,
                                    @Nonnull AnimationSlot slot,
                                    @Nonnull String animationId,
                                    long now,
                                    long resendIntervalMs) {
        String current = pitch ? flight.getPitchPoseAnimationId() : flight.getRollPoseAnimationId();
        long nextAt = pitch ? flight.getNextPitchPoseAnimationAtMs() : flight.getNextRollPoseAnimationAtMs();
        if (animationId.isBlank()) {
            clearPoseAnimation(ref, commandBuffer, flight, pitch, slot);
            return;
        }
        if (animationId.equals(current) && now < nextAt) {
            return;
        }
        AnimationUtils.playAnimation(ref, slot, animationId, true, commandBuffer);
        setPoseAnimationState(flight, pitch, animationId, now + resendIntervalMs);
    }

    private void clearPoseAnimation(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull AvatarFlightComponent flight,
                                    boolean pitch,
                                    @Nonnull AnimationSlot slot) {
        String current = pitch ? flight.getPitchPoseAnimationId() : flight.getRollPoseAnimationId();
        if (current.isBlank()) {
            return;
        }
        AnimationUtils.stopAnimation(ref, slot, true, commandBuffer);
        setPoseAnimationState(flight, pitch, "", 0L);
    }

    private void setPoseAnimationState(@Nonnull AvatarFlightComponent flight,
                                       boolean pitch,
                                       @Nonnull String animationId,
                                       long nextAtMs) {
        if (pitch) {
            flight.setPitchPoseAnimationId(animationId);
            flight.setNextPitchPoseAnimationAtMs(nextAtMs);
        } else {
            flight.setRollPoseAnimationId(animationId);
            flight.setNextRollPoseAnimationAtMs(nextAtMs);
        }
    }

    private static boolean isPoseSlot(@Nonnull AnimationSlot slot,
                                      @Nullable AnimationSlot pitchSlot,
                                      @Nullable AnimationSlot rollSlot) {
        return slot == pitchSlot || slot == rollSlot;
    }

    @Nonnull
    private static AnimationSlot resolveAnimationSlot(@Nullable String name, @Nonnull AnimationSlot fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (AnimationSlot slot : AnimationSlot.VALUES) {
            if (slot.name().toUpperCase(Locale.ROOT).equals(normalized)) {
                return slot;
            }
        }
        return fallback;
    }

    private void resetVisualPose(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TransformComponent transform = commandBuffer.getComponent(ref, transformType);
        if (transform != null && transform.getRotation() != null) {
            transform.getRotation().setPitch(0.0f);
            transform.getRotation().setRoll(0.0f);
            commandBuffer.putComponent(ref, transformType, transform);
        }
        HeadRotation headRotation = commandBuffer.getComponent(ref, headRotationType);
        if (headRotation != null && headRotation.getRotation() != null) {
            headRotation.getRotation().setPitch(0.0f);
            headRotation.getRotation().setRoll(0.0f);
            commandBuffer.putComponent(ref, headRotationType, headRotation);
        }
    }

    private void applyVisualPose(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull AvatarFlightController.Input input,
                                 @Nonnull AvatarFlightController.Output output) {
        TransformComponent transform = commandBuffer.getComponent(ref, transformType);
        if (transform != null && transform.getRotation() != null) {
            transform.getRotation().setYaw((float) input.yawRadians());
            transform.getRotation().setPitch((float) output.visualPitchRadians());
            transform.getRotation().setRoll((float) output.visualRollRadians());
            commandBuffer.putComponent(ref, transformType, transform);
        }
        HeadRotation headRotation = commandBuffer.getComponent(ref, headRotationType);
        if (headRotation != null && headRotation.getRotation() != null) {
            headRotation.getRotation().setYaw((float) input.yawRadians());
            headRotation.getRotation().setPitch((float) output.visualPitchRadians());
            headRotation.getRotation().setRoll((float) output.visualRollRadians());
            commandBuffer.putComponent(ref, headRotationType, headRotation);
        }
    }

    @Nonnull
    private AvatarFlightController.Input toControllerInput(@Nullable AvatarFlightInputComponent input,
                                                           @Nullable MovementStates states,
                                                           @Nonnull AvatarFlightComponent flight,
                                                           @Nonnull Ref<EntityStore> ref,
                                                           @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                           @Nonnull TwAvatarFlightConfig config) {
        long now = System.currentTimeMillis();
        boolean stale = input == null || input.isStale(now, config.getInput().getIntentTimeoutMs());
        double yaw = stale ? resolveYaw(ref, commandBuffer) : input.getYawRadians();
        double pitch = stale ? resolvePitch(ref, commandBuffer) : input.getPitchRadians();
        boolean onGround = stale ? states == null || states.onGround : input.isOnGround();
        boolean reinsFlap = input != null && input.consumeReinsFlap(
                now,
                Math.round(config.getInput().getIntentTimeoutMs())
        );
        boolean reinsAirbrake = input != null && input.isReinsAirbrakeActive(now);
        boolean reinsBoost = input != null && input.consumeReinsBoost(
                now,
                Math.round(config.getInput().getIntentTimeoutMs())
        );
        boolean sprintBoost = input != null && input.consumeSprintBoost(
                now,
                Math.round(config.getInput().getIntentTimeoutMs())
        );
        boolean launchRelease = input != null && input.consumeLaunchRelease(
                now,
                Math.round(config.getInput().getIntentTimeoutMs())
        );
        long launchHoldMs = launchRelease && input != null ? input.getLaunchHoldMs() : 0L;
        boolean activeFlight = flight.getMode() != AvatarFlightMode.GROUNDED;
        boolean itemFlightStart = reinsFlap || reinsBoost;
        boolean jumpIntent = activeFlight
                ? reinsFlap || (!stale && input.isJumping())
                : reinsFlap;
        boolean boostIntent = reinsBoost || (activeFlight && sprintBoost);
        AvatarFlightController.Input controllerInput = new AvatarFlightController.Input(
                stale ? 0.0 : input.getForwardAxis(),
                stale ? 0.0 : input.getStrafeAxis(),
                stale ? 0.0 : input.getVerticalAxis(),
                jumpIntent,
                !stale && input.isCrouching(),
                boostIntent,
                reinsAirbrake,
                onGround && !itemFlightStart,
                yaw,
                pitch,
                true,
                true,
                true,
                launchHoldMs
        );
        if (input != null) {
            input.clearTransientVerticalIntent();
        }
        return controllerInput;
    }

    @Nullable
    private MovementStates resolveMovementStates(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        MovementStatesComponent component = commandBuffer.getComponent(ref, movementStatesType);
        return component == null ? null : component.getMovementStates();
    }

    private double resolveYaw(@Nonnull Ref<EntityStore> ref, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Rotation3f rotation = resolveRotation(ref, commandBuffer);
        return rotation == null ? 0.0 : rotation.yaw();
    }

    private double resolvePitch(@Nonnull Ref<EntityStore> ref, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Rotation3f rotation = resolveRotation(ref, commandBuffer);
        return rotation == null ? 0.0 : rotation.pitch();
    }

    @Nullable
    private Rotation3f resolveRotation(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        HeadRotation headRotation = commandBuffer.getComponent(ref, headRotationType);
        if (headRotation != null && headRotation.getRotation() != null) {
            return headRotation.getRotation();
        }
        TransformComponent transform = commandBuffer.getComponent(ref, transformType);
        return transform == null ? null : transform.getRotation();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }
}
