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
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.SavedMovementStates;
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
    private final AvatarFlightAnimationService animationService = new AvatarFlightAnimationService();
    private final AvatarFlightBoostVfxService boostVfxService = new AvatarFlightBoostVfxService();
    private final AvatarFlightAbilityAudioService abilityAudioService = new AvatarFlightAbilityAudioService();
    private final AvatarFlightFlapAudioService flapAudioService = new AvatarFlightFlapAudioService();
    private final AvatarFlightLaunchVfxService launchVfxService = new AvatarFlightLaunchVfxService();
    private final AvatarFlightLaunchAudioService launchAudioService = new AvatarFlightLaunchAudioService();
    private final AvatarFlightTrailService trailService = new AvatarFlightTrailService();
    private final AvatarFlightGroundMovementService groundMovementService =
            new AvatarFlightGroundMovementService();
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
        groundMovementService.sync(
                ref,
                commandBuffer,
                flight,
                config.getMovement().getGroundedMoveSpeed(),
                output.mode() == AvatarFlightMode.GROUNDED
                        && controllerInput.onGround()
                        && !controllerInput.inFluid()
        );
        spendAppliedVigour(flight, config, output, now);
        TransformComponent transform = commandBuffer.getComponent(ref, transformType);
        boostVfxService.emitApplied(
                output,
                config,
                transform,
                controllerInput.yawRadians(),
                ref,
                commandBuffer
        );
        abilityAudioService.emitApplied(output, config, transform, commandBuffer);
        flapAudioService.tick(flight, output, config, transform, now, commandBuffer);
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
        trailService.tick(flight, output, config, now, ref, commandBuffer);
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
        boolean hasFlightVisualOverrides = flight.isClientFlyingSynced() || animationService.hasOverrides(flight);
        boolean suppressingOverlays =
                AvatarFlightAnimationService.shouldSuppressPlayerOverlayAnimations(
                        config, applyingVelocity, hasFlightVisualOverrides);
        boolean groundedMovementIntent = hasGroundedMovementIntent(controllerInput, config);
        syncOwnerClientFlyingState(ref, commandBuffer, flight, applyingVelocity);
        animationService.tick(
                ref, commandBuffer, flight, config, output, applyingVelocity, suppressingOverlays,
                groundedMovementIntent, controllerInput.inFluid(), now);
        if (applyingVelocity) {
            applyVisualPose(ref, commandBuffer, controllerInput, output);
            velocity.addInstruction(
                    new Vector3d(output.velocityX(), output.velocityY(), output.velocityZ()),
                    null,
                    ChangeVelocityType.Set
            );
            applyFlightMovementState(ref, commandBuffer, output);
        } else if (hasFlightVisualOverrides) {
            if (controllerInput.inFluid()) {
                releaseFlightMovementStateForSwimming(ref, commandBuffer);
            } else {
                clearFlightMovementState(ref, commandBuffer, controllerInput);
            }
            resetVisualPose(ref, commandBuffer);
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

    static boolean hasGroundedMovementIntent(@Nonnull AvatarFlightController.Input input,
                                               @Nonnull TwAvatarFlightConfig config) {
        return input.onGround()
                && (Math.abs(input.forwardAxis()) > config.getInput().getForwardDeadzone()
                || Math.abs(input.strafeAxis()) > config.getInput().getStrafeDeadzone());
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
                input.launchHoldMs(),
                input.airbrakeActivated(),
                input.inFluid()
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

    private void releaseFlightMovementStateForSwimming(@Nonnull Ref<EntityStore> ref,
                                                        @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        MovementStatesComponent component = commandBuffer.getComponent(ref, movementStatesType);
        if (component == null || component.getMovementStates() == null) {
            return;
        }
        MovementStates states = new MovementStates(component.getMovementStates());
        states.flying = false;
        states.gliding = false;
        component.setMovementStates(states);
        commandBuffer.putComponent(ref, movementStatesType, component);
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
        boolean inFluid = states != null && (states.inFluid || states.swimming);
        boolean reinsFlap = input != null && input.consumeReinsFlap(
                now,
                Math.round(config.getInput().getIntentTimeoutMs())
        );
        boolean reinsAirbrake = input != null && input.isReinsAirbrakeActive(now);
        boolean reinsAirbrakeActivated = input != null && input.consumeReinsAirbrakeActivation(
                now,
                Math.round(config.getInput().getIntentTimeoutMs())
        );
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
                launchHoldMs,
                reinsAirbrakeActivated,
                inFluid
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
