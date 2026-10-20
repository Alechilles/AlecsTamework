package com.alechilles.alecstamework.npc.turning;

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
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.MovementStatesSystem;
import com.hypixel.hytale.server.npc.util.NPCPhysicsMath;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plays a model's {@code TurnLeft} or {@code TurnRight} animation set while a standing NPC's
 * body turns in place.
 *
 * <p>Hytale picks movement animations on the client from {@code MovementStates}, which has no
 * turning state, so an NPC that rotates without moving keeps playing Idle. This system measures
 * the body yaw change after steering has run and overlays the turn clip on the
 * {@link AnimationSlot#ServerAction} slot, which vanilla roles and hit reactions leave alone. It is
 * opt-in per model: an NPC whose model has neither set costs one map lookup per tick.</p>
 *
 * <p>Runs on the world thread. The animation call sends packets directly and writes no
 * components; the runtime-only {@link TurnInPlaceState} is added and removed through the
 * command buffer.</p>
 */
public final class TurnInPlaceAnimationSystem extends EntityTickingSystem<EntityStore> {
    static final String TURN_LEFT = "TurnLeft";
    static final String TURN_RIGHT = "TurnRight";
    private static final AnimationSlot SLOT = AnimationSlot.ServerAction;

    /**
     * Seconds over which the measured yaw rate is smoothed. A standing NPC that turns to watch something
     * moves its body in short bursts as its head reaches the end of its range; smoothing makes those
     * bursts read as one turn instead of restarting the clip each time.
     */
    static final float RATE_SMOOTHING_SECONDS = 0.2f;
    /** Smoothed yaw rate that starts a turn clip, in radians per second (about 15 degrees). */
    static final float START_RATE = (float) Math.toRadians(15.0);
    /** Smoothed yaw rate below which a playing turn clip counts as slowing down (about 6 degrees). */
    static final float STOP_RATE = (float) Math.toRadians(6.0);
    /** Seconds the turn must stay slow before the clip stops, so gaps between bursts keep it playing. */
    static final float STOP_DELAY_SECONDS = 0.4f;
    /** Shortest time a clip plays once started, about one step cycle, before it may stop or flip. */
    static final float MIN_PLAY_SECONDS = 0.6f;
    /** A single-tick yaw jump larger than this is a teleport or spawn, not a turn. */
    static final float TELEPORT_ANGLE = (float) Math.toRadians(90.0);

    // MovementStatesSystem runs after SteeringSystem, the NPC yaw writer, so both yaw and flags are final.
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, MovementStatesSystem.class));

    private final ComponentType<EntityStore, TurnInPlaceState> stateType;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesType;
    private final ComponentType<EntityStore, ModelComponent> modelType;
    private final ComponentType<EntityStore, ActiveAnimationComponent> animationType;
    private final Query<EntityStore> query;

    public TurnInPlaceAnimationSystem(@Nonnull ComponentType<EntityStore, TurnInPlaceState> stateType) {
        this.stateType = stateType;
        this.npcType = NPCEntity.getComponentType();
        this.transformType = TransformComponent.getComponentType();
        this.movementStatesType = MovementStatesComponent.getComponentType();
        this.modelType = ModelComponent.getComponentType();
        this.animationType = ActiveAnimationComponent.getComponentType();
        this.query = Query.and(npcType, transformType, movementStatesType, modelType);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commands) {
        TurnInPlaceState state = chunk.getComponent(index, stateType);
        ModelComponent modelComponent = chunk.getComponent(index, modelType);
        Model model = modelComponent == null ? null : modelComponent.getModel();
        boolean hasTurnSets = model != null && hasTurnSets(model.getAnimationSetMap());
        if (!hasTurnSets) {
            if (state != null) {
                // The model changed to one without turn sets: stop our clip and forget the NPC.
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                stopClip(ref, chunk.getComponent(index, npcType), chunk.getComponent(index, animationType),
                        state, commands);
                commands.tryRemoveComponent(ref, stateType);
            }
            return;
        }
        TransformComponent transform = chunk.getComponent(index, transformType);
        if (transform == null) {
            return;
        }
        float yaw = transform.getRotation().yaw();
        if (state == null) {
            TurnInPlaceState created = new TurnInPlaceState();
            created.lastYaw = yaw;
            commands.addComponent(chunk.getReferenceTo(index), stateType, created);
            return;
        }

        MovementStatesComponent movement = chunk.getComponent(index, movementStatesType);
        boolean standing = movement != null && isStanding(movement.getMovementStates());
        float delta = Float.isNaN(state.lastYaw) ? 0f : NPCPhysicsMath.turnAngle(state.lastYaw, yaw);
        state.lastYaw = yaw;
        if (Math.abs(delta) > TELEPORT_ANGLE) {
            delta = 0f;
        }
        if (standing && dt > 0f) {
            state.smoothedRate += (delta / dt - state.smoothedRate) * Math.min(1f, dt / RATE_SMOOTHING_SECONDS);
        } else {
            state.smoothedRate = 0f;
        }
        int wanted = standing && dt > 0f ? nextDirection(state, state.smoothedRate, dt) : 0;
        if (wanted == state.direction) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        NPCEntity npc = chunk.getComponent(index, npcType);
        ActiveAnimationComponent animations = chunk.getComponent(index, animationType);
        if (wanted == 0) {
            stopClip(ref, npc, animations, state, commands);
            return;
        }
        if (npc == null || !slotFreeFor(animations, state)) {
            return;
        }
        npc.playAnimation(ref, SLOT, wanted > 0 ? TURN_LEFT : TURN_RIGHT, commands);
        state.direction = wanted;
        state.playedSeconds = 0f;
        state.slowSeconds = 0f;
    }

    /**
     * Returns the turn direction the NPC should show after this tick: 1 for left (yaw rising),
     * -1 for right, 0 for none. Advances the play and slow timers of a playing clip.
     */
    static int nextDirection(@Nonnull TurnInPlaceState state, float yawRate, float dt) {
        float speed = Math.abs(yawRate);
        int rateDirection = yawRate > 0f ? 1 : -1;
        if (state.direction == 0) {
            return speed >= START_RATE ? rateDirection : 0;
        }
        state.playedSeconds += dt;
        if (speed >= START_RATE && rateDirection != state.direction && state.playedSeconds >= MIN_PLAY_SECONDS) {
            return rateDirection;
        }
        state.slowSeconds = speed < STOP_RATE ? state.slowSeconds + dt : 0f;
        boolean stopped = state.slowSeconds >= STOP_DELAY_SECONDS && state.playedSeconds >= MIN_PLAY_SECONDS;
        return stopped ? 0 : state.direction;
    }

    /** Standing on the ground and not moving, in a state where stepping around makes sense. */
    static boolean isStanding(@Nonnull MovementStates states) {
        return states.horizontalIdle && !states.walking && !states.running && !states.sprinting
                && !states.jumping && !states.falling && !states.flying && !states.swimming
                && !states.climbing && !states.sitting && !states.sleeping && !states.mounting;
    }

    private static boolean hasTurnSets(@Nullable Map<String, ?> animationSets) {
        return animationSets != null && animationSets.containsKey(TURN_LEFT) && animationSets.containsKey(TURN_RIGHT);
    }

    /** The slot is free, or holds the clip this system started; never replace another owner's clip. */
    private static boolean slotFreeFor(@Nullable ActiveAnimationComponent animations, @Nonnull TurnInPlaceState state) {
        if (animations == null) {
            return true;
        }
        String current = animations.getActiveAnimations()[SLOT.ordinal()];
        return current == null || (state.direction != 0 && ownClip(current));
    }

    private void stopClip(@Nonnull Ref<EntityStore> ref, @Nullable NPCEntity npc,
                          @Nullable ActiveAnimationComponent animations, @Nonnull TurnInPlaceState state,
                          @Nonnull CommandBuffer<EntityStore> commands) {
        if (state.direction == 0) {
            return;
        }
        state.direction = 0;
        state.playedSeconds = 0f;
        state.slowSeconds = 0f;
        String current = animations == null ? null : animations.getActiveAnimations()[SLOT.ordinal()];
        if (npc != null && ownClip(current)) {
            npc.playAnimation(ref, SLOT, null, commands);
        }
    }

    private static boolean ownClip(@Nullable String animationId) {
        return TURN_LEFT.equals(animationId) || TURN_RIGHT.equals(animationId);
    }
}
