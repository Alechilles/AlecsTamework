package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.movement.NativeSwimRiderComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.ecs.BreathingCheckEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCSystems;
import java.util.Set;
import javax.annotation.Nonnull;

/** Preserves the source role's breathing rules while native mounting parks its AI. */
public final class NativeSwimBreathingSystem extends EntityEventSystem<EntityStore, BreathingCheckEvent> {
    public NativeSwimBreathingSystem() { super(BreathingCheckEvent.class); }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                       @Nonnull BreathingCheckEvent event) {
        var mount = chunk.getComponent(index, NPCMountComponent.getComponentType());
        var owner = mount.getOwnerPlayerRef();
        if (owner == null) return;
        var riderRef = store.getExternalData().getWorld().getEntityRef(owner.getUuid());
        if (riderRef == null || !riderRef.isValid()) return;
        var rider = buffer.getComponent(riderRef, NativeSwimRiderComponent.getComponentType());
        var mountId = chunk.getComponent(index, UUIDComponent.getComponentType()).getUuid();
        if (rider == null || rider.settings == null || !mountId.equals(rider.mountUuid)) return;
        event.setCanBreathe(rider.canBreathe(event.getBreathingMaterial(), event.getFluidId()));
    }

    @Override @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.and(NPCEntity.getComponentType(), NPCMountComponent.getComponentType(), UUIDComponent.getComponentType());
    }

    @Override @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER, NPCSystems.BreathingCheckEventSystem.class));
    }
}
