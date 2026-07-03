package com.alechilles.alecstamework.debug;

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
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Logs movement input and processed player state for players enabled through the debug command.
 */
public final class PlayerInputDebugSystem extends EntityTickingSystem<EntityStore> {
    private static final long THROTTLE_MS = 250L;

    private final ComponentType<EntityStore, PlayerInput> playerInputType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesType;
    private final ComponentType<EntityStore, HeadRotation> headRotationType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, Velocity> velocityType;
    private final ComponentType<EntityStore, ModelComponent> modelType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class)
    );
    private final ConcurrentHashMap<UUID, Snapshot> lastSnapshots = new ConcurrentHashMap<>();

    public PlayerInputDebugSystem(@Nonnull ComponentType<EntityStore, PlayerInput> playerInputType,
                                  @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
                                  @Nonnull ComponentType<EntityStore, MovementStatesComponent> movementStatesType,
                                  @Nonnull ComponentType<EntityStore, HeadRotation> headRotationType,
                                  @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                                  @Nullable ComponentType<EntityStore, Velocity> velocityType,
                                  @Nonnull ComponentType<EntityStore, ModelComponent> modelType) {
        this.playerInputType = playerInputType;
        this.uuidType = uuidType;
        this.movementStatesType = movementStatesType;
        this.headRotationType = headRotationType;
        this.transformType = transformType;
        this.velocityType = velocityType == null ? Velocity.getComponentType() : velocityType;
        this.modelType = modelType;
        this.query = Query.and(playerInputType, uuidType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, uuidType);
        PlayerInput playerInput = archetypeChunk.getComponent(index, playerInputType);
        UUID playerUuid = uuidComponent == null ? null : uuidComponent.getUuid();
        if (playerInput == null || !PlayerInputDebugProbe.isEnabled(playerUuid)) {
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        String queue = summarizeQueue(playerInput.getMovementUpdateQueue());
        String states = formatMovementStates(store.getComponent(ref, movementStatesType));
        String head = formatHeadRotation(store.getComponent(ref, headRotationType));
        String position = formatPosition(store.getComponent(ref, transformType));
        String velocity = formatVelocity(store.getComponent(ref, velocityType));
        String model = formatModel(store.getComponent(ref, modelType));
        String signature = "queue=" + queue + "|states=" + states + "|head=" + head
                + "|pos=" + position + "|velocity=" + velocity + "|model=" + model
                + "|mountId=" + playerInput.getMountId();
        if (!shouldLog(playerUuid, signature)) {
            return;
        }

        com.alechilles.alecstamework.Tamework instance = com.alechilles.alecstamework.Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.INFO).log(String.format(
                    "TameworkInput debug: tick player=%s mountId=%s queue=%s states=%s head=%s pos=%s velocity=%s model=%s",
                    playerUuid,
                    playerInput.getMountId(),
                    queue,
                    states,
                    head,
                    position,
                    velocity,
                    model
            ));
        }
    }

    private boolean shouldLog(@Nonnull UUID playerUuid, @Nonnull String signature) {
        long now = System.currentTimeMillis();
        Snapshot previous = lastSnapshots.get(playerUuid);
        if (previous != null && signature.equals(previous.signature()) && now - previous.loggedAtMs() < THROTTLE_MS) {
            return false;
        }
        lastSnapshots.put(playerUuid, new Snapshot(signature, now));
        return true;
    }

    @Nonnull
    private static String summarizeQueue(@Nonnull List<PlayerInput.InputUpdate> queue) {
        if (queue.isEmpty()) {
            return "<empty>";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(queue.size(), 8);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(';');
            }
            appendInputUpdate(builder, queue.get(i));
        }
        if (queue.size() > count) {
            builder.append(";...+").append(queue.size() - count);
        }
        return builder.toString();
    }

    private static void appendInputUpdate(@Nonnull StringBuilder builder, @Nonnull PlayerInput.InputUpdate update) {
        if (update instanceof PlayerInput.WishMovement wish) {
            builder.append("Wish(").append(format(wish.getX())).append('/').append(format(wish.getY()))
                    .append('/').append(format(wish.getZ())).append(')');
        } else if (update instanceof PlayerInput.RelativeMovement relative) {
            builder.append("Relative(").append(format(relative.getX())).append('/').append(format(relative.getY()))
                    .append('/').append(format(relative.getZ())).append(')');
        } else if (update instanceof PlayerInput.AbsoluteMovement absolute) {
            builder.append("Absolute(").append(format(absolute.getX())).append('/').append(format(absolute.getY()))
                    .append('/').append(format(absolute.getZ())).append(')');
        } else if (update instanceof PlayerInput.SetClientVelocity velocity) {
            builder.append("Velocity(").append(PlayerInputDebugProbe.formatJomlVector(velocity.getVelocity())).append(')');
        } else if (update instanceof PlayerInput.SetBody body) {
            builder.append("Body(").append(PlayerInputDebugProbe.formatDirection(body.direction())).append(')');
        } else if (update instanceof PlayerInput.SetHead head) {
            builder.append("Head(").append(PlayerInputDebugProbe.formatDirection(head.direction())).append(')');
        } else if (update instanceof PlayerInput.SetMovementStates states) {
            builder.append("States(").append(PlayerInputDebugProbe.formatStates(states.movementStates())).append(')');
        } else if (update instanceof PlayerInput.SetRiderMovementStates states) {
            builder.append("RiderStates(").append(PlayerInputDebugProbe.formatStates(states.movementStates())).append(')');
        } else {
            builder.append(update.getClass().getSimpleName());
        }
    }

    @Nonnull
    private static String formatMovementStates(@Nullable MovementStatesComponent component) {
        MovementStates states = component == null ? null : component.getMovementStates();
        return PlayerInputDebugProbe.formatStates(states);
    }

    @Nonnull
    private static String formatHeadRotation(@Nullable HeadRotation headRotation) {
        if (headRotation == null || headRotation.getRotation() == null) {
            return "<none>";
        }
        return String.format(
                "%.3f/%.3f/%.3f",
                headRotation.getRotation().yaw(),
                headRotation.getRotation().pitch(),
                headRotation.getRotation().roll()
        );
    }

    @Nonnull
    private static String formatPosition(@Nullable TransformComponent transform) {
        return transform == null || transform.getPosition() == null
                ? "<none>"
                : PlayerInputDebugProbe.formatJomlVector(transform.getPosition());
    }

    @Nonnull
    private static String formatVelocity(@Nullable Velocity velocity) {
        Vector3d current = velocity == null ? null : velocity.getVelocity();
        return PlayerInputDebugProbe.formatJomlVector(current);
    }

    @Nonnull
    private static String formatModel(@Nullable ModelComponent modelComponent) {
        return modelComponent == null || modelComponent.getModel() == null
                ? "<none>"
                : modelComponent.getModel().getModelAssetId();
    }

    @Nonnull
    private static String format(double value) {
        return String.format("%.3f", value);
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

    private record Snapshot(String signature, long loggedAtMs) {
    }
}
