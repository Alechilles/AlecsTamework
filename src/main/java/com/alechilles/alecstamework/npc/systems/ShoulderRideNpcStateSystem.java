package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkShoulderRideComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
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
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.MovementStatesSystem;
import java.util.Set;
import javax.annotation.Nonnull;

/** Suspends and restores shoulder-mounted NPC behavior and physical presence. */
public final class ShoulderRideNpcStateSystem
        extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkShoulderRideComponent> markerType;
    private final ComponentType<EntityStore, MountedComponent> mountedType;
    private final ComponentType<EntityStore, Interactable> interactableType;
    private final ComponentType<EntityStore, Intangible> intangibleType;
    private final ComponentType<EntityStore, Invulnerable> invulnerableType;
    private final ComponentType<EntityStore, Frozen> frozenType;
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER,
                    ShoulderRideNpcFollowSystem.class),
            new SystemDependency<>(Order.AFTER, MovementStatesSystem.class));

    public ShoulderRideNpcStateSystem(
            ComponentType<EntityStore, TameworkShoulderRideComponent> markerType,
            ComponentType<EntityStore, MountedComponent> mountedType,
            ComponentType<EntityStore, Interactable> interactableType,
            ComponentType<EntityStore, Intangible> intangibleType,
            ComponentType<EntityStore, Invulnerable> invulnerableType,
            ComponentType<EntityStore, Frozen> frozenType,
            ComponentType<EntityStore, MovementStatesComponent> movementStatesType) {
        this.markerType = markerType;
        this.mountedType = mountedType;
        this.interactableType = interactableType;
        this.intangibleType = intangibleType;
        this.invulnerableType = invulnerableType;
        this.frozenType = frozenType;
        this.movementStatesType = movementStatesType;
        this.query = Query.and(markerType, NPCEntity.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commands) {
        Ref<EntityStore> npcRef = chunk.getReferenceTo(index);
        TameworkShoulderRideComponent marker =
                chunk.getComponent(index, markerType);
        if (marker == null) return;

        boolean mounted = commands.getComponent(npcRef, mountedType) != null;
        if (mounted) {
            suspendNpc(npcRef, commands);
            return;
        }
        restoreNpc(npcRef, marker, commands);
    }

    private void suspendNpc(
            Ref<EntityStore> npcRef, CommandBuffer<EntityStore> commands) {
        commands.ensureComponent(npcRef, frozenType);
        commands.ensureComponent(npcRef, intangibleType);
        commands.ensureComponent(npcRef, invulnerableType);
        commands.tryRemoveComponent(npcRef, interactableType);
        MovementStatesComponent component = commands.getComponent(npcRef,
                movementStatesType);
        if (component != null
                && normalizeMountedMovementStates(component.getMovementStates())) {
            NPCEntity npc = commands.getComponent(npcRef,
                    NPCEntity.getComponentType());
            if (npc != null) {
                npc.playAnimation(npcRef, AnimationSlot.Movement, null,
                        commands);
            }
        }
    }

    static boolean normalizeMountedMovementStates(
            @Nonnull MovementStates states) {
        boolean changed = !states.idle || !states.horizontalIdle
                || states.walking || states.running || states.jumping
                || states.flying || states.falling || states.swimming;
        states.idle = true;
        states.horizontalIdle = true;
        states.walking = false;
        states.running = false;
        states.jumping = false;
        states.flying = false;
        states.falling = false;
        states.swimming = false;
        return changed;
    }

    private void restoreNpc(
            Ref<EntityStore> npcRef, TameworkShoulderRideComponent marker,
            CommandBuffer<EntityStore> commands) {
        if (!marker.hasCapturedState()) {
            commands.tryRemoveComponent(npcRef, markerType);
            return;
        }
        if (!marker.wasFrozen()) {
            commands.tryRemoveComponent(npcRef, frozenType);
        }
        if (!marker.wasIntangible()) {
            commands.tryRemoveComponent(npcRef, intangibleType);
        }
        if (!marker.wasInvulnerable()) {
            commands.tryRemoveComponent(npcRef, invulnerableType);
        }
        if (marker.wasInteractable()) {
            commands.ensureComponent(npcRef, interactableType);
        }
        commands.tryRemoveComponent(npcRef, markerType);
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
