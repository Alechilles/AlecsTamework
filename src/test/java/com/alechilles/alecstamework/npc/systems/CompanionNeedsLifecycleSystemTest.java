package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsRuntimeRegistry;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies needs membership and due-work cleanup through real ECS callbacks. */
class CompanionNeedsLifecycleSystemTest {
    @Test
    void entityRemovalCallbackClearsMembershipAndDueWork() {
        ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = new ComponentType<>();
        CompanionNeedsRuntimeRegistry registry = new CompanionNeedsRuntimeRegistry();
        CompanionNeedsLifecycleSystem system = new CompanionNeedsLifecycleSystem(
                registry,
                npcType,
                tamedType
        );
        UUID npcId = UUID.randomUUID();

        try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            Ref<EntityStore> reference = store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(npcId);
            store.put(reference, npcType, npc);
            store.put(reference, tamedType, new TameworkTamedComponent(true));

            system.onEntityAdded(reference, AddReason.SPAWN, store, null);

            CompanionNeedsRuntimeRegistry.WorldState state = registry.state(store);
            assertTrue(state.membership().contains(npcId));
            assertTrue(state.hasDue(Long.MAX_VALUE));

            system.onEntityRemove(reference, RemoveReason.REMOVE, store, null);

            assertFalse(state.membership().contains(npcId));
            assertFalse(state.hasDue(Long.MAX_VALUE));
        }
    }

    @Test
    void tamedComponentRemovalCallbackClearsMembershipAndDueWork() {
        ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = new ComponentType<>();
        CompanionNeedsRuntimeRegistry registry = new CompanionNeedsRuntimeRegistry();
        CompanionNeedsTamedChangeSystem system = new CompanionNeedsTamedChangeSystem(
                registry,
                npcType,
                tamedType
        );
        UUID npcId = UUID.randomUUID();

        try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            Ref<EntityStore> reference = store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(npcId);
            store.put(reference, npcType, npc);
            store.put(reference, tamedType, new TameworkTamedComponent(true));

            system.onComponentAdded(reference, new TameworkTamedComponent(true), store, null);

            CompanionNeedsRuntimeRegistry.WorldState state = registry.state(store);
            assertTrue(state.membership().contains(npcId));
            assertTrue(state.hasDue(Long.MAX_VALUE));

            system.onComponentRemoved(reference, new TameworkTamedComponent(true), store, null);

            assertFalse(state.membership().contains(npcId));
            assertFalse(state.hasDue(Long.MAX_VALUE));
        }
    }
}
