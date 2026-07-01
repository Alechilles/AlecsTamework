package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.movement.BodyMotionTameworkMountedGlide;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.systems.AvoidanceSystem;
import com.hypixel.hytale.server.npc.systems.RoleSystems;
import com.hypixel.hytale.server.npc.systems.SteeringSystem;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Keeps mounted glide NPCs in their configured ridden state and motion controller.
 */
public final class MountedGlideStateSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType;
    private final ComponentType<EntityStore, NPCEntity> npcEntityComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, AvoidanceSystem.class),
            new SystemDependency<>(Order.AFTER, RoleSystems.BehaviourTickSystem.class),
            new SystemDependency<>(Order.BEFORE, SteeringSystem.class)
    );
    private long lastDebugMs;

    public MountedGlideStateSystem(
            @Nonnull ComponentType<EntityStore, TameworkMountedGlideComponent> mountComponentType,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcEntityComponentType) {
        this.mountComponentType = mountComponentType;
        this.npcEntityComponentType = npcEntityComponentType;
        this.query = Query.and(mountComponentType, npcEntityComponentType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TameworkMountedGlideComponent mount = archetypeChunk.getComponent(index, mountComponentType);
        NPCEntity npc = archetypeChunk.getComponent(index, npcEntityComponentType);
        if (mount == null || npc == null || npc.getRole() == null || mount.getRiderUuid().isBlank()) {
            return;
        }
        Ref<EntityStore> mountRef = archetypeChunk.getReferenceTo(index);
        Role role = npc.getRole();
        String controllerBefore = controllerName(role);
        ensureGlideState(mountRef, role, mount.getGlideState(), commandBuffer);
        ensureGlideController(mountRef, npc, role, mount.getGlideController(), commandBuffer);
        BodyMotionTameworkMountedGlide.applyGlideSteering(
                mountRef,
                role,
                mount,
                dt,
                role.getBodySteering(),
                commandBuffer
        );
        logDebug(role, controllerBefore);
    }

    private void ensureGlideState(@Nonnull Ref<EntityStore> mountRef,
                                  @Nonnull Role role,
                                  @Nonnull String state,
                                  @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        StateSupport support = role.getStateSupport();
        if (support == null || state.isBlank() || isMissingState(support, state) || inState(support, state)) {
            return;
        }
        String subState = support.getStateHelper() == null ? "" : support.getStateHelper().getDefaultSubState();
        support.setState(mountRef, state, subState == null ? "" : subState, commandBuffer);
    }

    private boolean inState(@Nonnull StateSupport support, @Nonnull String state) {
        if (support.getStateHelper() == null) {
            return state.equals(support.getStateName());
        }
        int stateIndex = support.getStateHelper().getStateIndex(state);
        return stateIndex >= 0 && support.inState(stateIndex);
    }

    private boolean isMissingState(@Nonnull StateSupport support, @Nonnull String state) {
        return support.getStateHelper() != null
                && support.getStateHelper().getStateIndex(state) == StateSupport.NO_STATE;
    }

    private void ensureGlideController(@Nonnull Ref<EntityStore> mountRef,
                                       @Nonnull NPCEntity npc,
                                       @Nonnull Role role,
                                       @Nonnull String controller,
                                       @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (isActiveController(role.getActiveMotionController(), controller)) {
            return;
        }
        if (!controller.isBlank()) {
            role.setActiveMotionController(mountRef, npc, controller, commandBuffer);
        }
    }

    private boolean isActiveController(MotionController active, @Nonnull String controller) {
        return active != null && !controller.isBlank() && controller.equals(active.getType());
    }

    private void logDebug(@Nonnull Role role, @Nonnull String controllerBefore) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDebugMs < 1000) {
            return;
        }
        lastDebugMs = now;
        instance.getLogger().at(Level.INFO).log(
                "TameworkGlide debug: stateSystem state=%s controllerBefore=%s controllerAfter=%s bodyMotion=%s steering=%s/%s/%s",
                role.getStateSupport() == null ? "<none>" : role.getStateSupport().getStateName(),
                controllerBefore,
                controllerName(role),
                role.getSteeringMotionName() == null ? "<none>" : role.getSteeringMotionName(),
                role.getBodySteering().getTranslation().x,
                role.getBodySteering().getTranslation().y,
                role.getBodySteering().getTranslation().z
        );
    }

    @Nonnull
    private String controllerName(@Nonnull Role role) {
        MotionController active = role.getActiveMotionController();
        return active == null ? "<none>" : active.getType();
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
