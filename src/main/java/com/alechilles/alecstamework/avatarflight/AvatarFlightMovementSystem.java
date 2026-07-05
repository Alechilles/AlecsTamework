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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
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
    private long nextDebugLogAtMs;
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
        AvatarFlightController.Input controllerInput =
                toControllerInput(input, movementStates, ref, commandBuffer, config);
        if (input != null) {
            commandBuffer.putComponent(ref, inputType, input);
        }
        long now = System.currentTimeMillis();
        AvatarFlightController.State state = AvatarFlightController.State.from(flight);
        AvatarFlightController.Output output = AvatarFlightController.update(
                state,
                controllerInput,
                config,
                Math.max(0.0, dt),
                now
        );
        flight.setMode(output.mode());
        flight.setVelocity(output.velocityX(), output.velocityY(), output.velocityZ());
        flight.setNextJumpAtMs(output.nextJumpAtMs());
        flight.setNextBoostAtMs(output.nextBoostAtMs());
        syncOwnerClientFlyingState(ref, commandBuffer, flight, output.applyVelocity());
        applyVisualPose(ref, commandBuffer, controllerInput, output);
        suppressPlayerOverlayAnimations(ref, commandBuffer, flight, config);
        if (output.applyVelocity()) {
            velocity.addInstruction(
                    new Vector3d(output.velocityX(), output.velocityY(), output.velocityZ()),
                    null,
                    ChangeVelocityType.Set
            );
            applyFlightMovementState(ref, commandBuffer, output);
            applyMovementAnimation(ref, commandBuffer, flight, config, output);
        } else {
            clearMovementAnimation(ref, commandBuffer, flight);
        }
        commandBuffer.putComponent(ref, flightType, flight);
        maybeLogDebug(config, ref, controllerInput, output, movementStates);
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
        states.sprinting = states.sprinting || output.fastFlight();
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

    private void suppressPlayerOverlayAnimations(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                 @Nonnull AvatarFlightComponent flight,
                                                 @Nonnull TwAvatarFlightConfig config) {
        if (!config.getAnimation().isSuppressNonMovementAnimations()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < flight.getNextSuppressedAnimationAtMs()) {
            return;
        }
        if (config.getAnimation().isSuppressActionAnimation()) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, commandBuffer);
        }
        if (config.getAnimation().isSuppressStatusAnimation()) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, commandBuffer);
        }
        if (config.getAnimation().isSuppressEmoteAnimation()) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Emote, true, commandBuffer);
        }
        if (config.getAnimation().isSuppressFaceAnimation()) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Face, true, commandBuffer);
        }
        flight.setNextSuppressedAnimationAtMs(now + config.getAnimation().getSuppressionIntervalMs());
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
                                                           @Nonnull Ref<EntityStore> ref,
                                                           @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                           @Nonnull TwAvatarFlightConfig config) {
        long now = System.currentTimeMillis();
        boolean stale = input == null || input.isStale(now, config.getInput().getIntentTimeoutMs());
        double yaw = stale ? resolveYaw(ref, commandBuffer) : input.getYawRadians();
        double pitch = stale ? resolvePitch(ref, commandBuffer) : input.getPitchRadians();
        boolean onGround = stale ? states == null || states.onGround : input.isOnGround();
        boolean liveSprint = states != null && states.sprinting;
        boolean reinsFlap = input != null && input.consumeReinsFlap(
                now,
                Math.round(config.getInput().getIntentTimeoutMs())
        );
        boolean reinsAirbrake = input != null && input.isReinsAirbrakeActive(now);
        AvatarFlightController.Input controllerInput = new AvatarFlightController.Input(
                stale ? 0.0 : input.getForwardAxis(),
                stale ? 0.0 : input.getStrafeAxis(),
                stale ? 0.0 : input.getVerticalAxis(),
                reinsFlap || (!stale && input.isJumping()),
                !stale && input.isCrouching(),
                liveSprint || (!stale && input.isSprinting()),
                reinsAirbrake,
                onGround,
                yaw,
                pitch
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

    private void maybeLogDebug(@Nonnull TwAvatarFlightConfig config,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull AvatarFlightController.Input input,
                               @Nonnull AvatarFlightController.Output output,
                               @Nullable MovementStates states) {
        if (!config.getDebug().isLogControllerTicks()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextDebugLogAtMs) {
            return;
        }
        nextDebugLogAtMs = now + TimeUnit.SECONDS.toMillis(1L);
        com.alechilles.alecstamework.Tamework instance = com.alechilles.alecstamework.Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(String.format(
                "TameworkAvatarFlight debug: ref=%s mode=%s input=%.2f/%.2f/%.2f jump=%s crouch=%s sprint=%s airbrake=%s onGround=%s"
                        + " output=%.2f/%.2f/%.2f apply=%s jumpApplied=%s boostApplied=%s animIdle=%s animFast=%s"
                        + " visual=%.1f/%.1f states=%s",
                ref,
                output.mode(),
                input.forwardAxis(),
                input.strafeAxis(),
                input.verticalAxis(),
                input.jump(),
                input.crouch(),
                input.sprint(),
                input.airbrake(),
                input.onGround(),
                output.velocityX(),
                output.velocityY(),
                output.velocityZ(),
                output.applyVelocity(),
                output.jumpApplied(),
                output.boostApplied(),
                output.horizontalIdle(),
                output.fastFlight(),
                Math.toDegrees(output.visualPitchRadians()),
                Math.toDegrees(output.visualRollRadians()),
                formatMovementStates(states)
        ));
    }

    @Nonnull
    private static String formatMovementStates(@Nullable MovementStates states) {
        if (states == null) {
            return "<none>";
        }
        return String.format(
                "fly=%s ground=%s idle=%s hIdle=%s sprint=%s run=%s walk=%s jump=%s crouch=%s fall=%s far=%s",
                states.flying,
                states.onGround,
                states.idle,
                states.horizontalIdle,
                states.sprinting,
                states.running,
                states.walking,
                states.jumping,
                states.crouching,
                states.falling,
                states.fallingFar
        );
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
