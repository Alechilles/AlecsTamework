package com.alechilles.alecstamework.companion.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.testsupport.EcsFixture;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.Dirty;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * The change detector on a real ECS store (EcsFixture): its query, the tracker it attaches through
 * the command buffer, and the Dirty mark Hytale saves by. A regression here is a companion whose
 * owner changed but whose chunk is never saved, so the change is lost on restart.
 */
class CompanionChangeDetectorSystemTest {
    private static final long DISCRETE_MS = 2_000L;

    @Test
    void anOwnerChangeMarksTheBodyDirtyAtItsNextCheckAndNoChangeDoesNot() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (EcsFixture ecs = new EcsFixture()) {
            ComponentType<EntityStore, NPCEntity> npc = ecs.component(NPCEntity.class, () -> allocate(NPCEntity.class));
            ComponentType<EntityStore, TameworkOwnerComponent> owner =
                    ecs.component(TameworkOwnerComponent.class, TameworkOwnerComponent::new);
            ComponentType<EntityStore, CompanionChangeTracker> tracker =
                    ecs.component(CompanionChangeTracker.class, CompanionChangeTracker::new);
            ComponentType<EntityStore, Dirty> dirty = ecs.component(Dirty.class, Dirty::new);
            ecs.entityModuleType("dirtyComponentType", dirty);
            CompanionFingerprints fingerprints = new CompanionFingerprints(
                    List.of(new CompanionFingerprints.Encoded<>(owner, TameworkOwnerComponent.CODEC)), List.of(),
                    ecs.component(TameworkLevelingComponent.class, TameworkLevelingComponent::new),
                    ecs.component(TameworkLifeStageComponent.class, TameworkLifeStageComponent::new),
                    ecs.component(TameworkBreedingComponent.class, TameworkBreedingComponent::new),
                    ecs.component(TameworkNeedsComponent.class, TameworkNeedsComponent::new),
                    ecs.component(TameworkHappinessComponent.class, TameworkHappinessComponent::new));
            ecs.system(new CompanionChangeDetectorSystem(npc, owner, tracker, fingerprints, clock::get,
                    DISCRETE_MS, 60_000L));

            Ref<EntityStore> pet = ecs.spawn(h -> {
                h.addComponent(npc, allocate(NPCEntity.class));
                h.addComponent(owner, new TameworkOwnerComponent(UUID.randomUUID(), "Owner"));
                h.addComponent(dirty, new Dirty());
            });

            ecs.tick(1);
            assertNotNull(ecs.get(pet, tracker), "the first tick attaches a tracker through the command buffer");
            assertTrue(ecs.get(pet, dirty).consumeDirty(), "a body seen for the first time is saved once");

            clock.addAndGet(3 * DISCRETE_MS);       // past any stagger: the next tick runs a check
            ecs.tick(1);
            assertFalse(ecs.get(pet, dirty).consumeDirty(), "no change: nothing to save");

            ecs.get(pet, owner).setOwnerId(UUID.randomUUID());
            ecs.tick(1);
            assertFalse(ecs.get(pet, dirty).isDirty(), "a change is only seen at the next check");
            clock.addAndGet(DISCRETE_MS);
            ecs.tick(1);
            assertTrue(ecs.get(pet, dirty).consumeDirty(), "the owner change marks the body for saving");
            assertEquals(1, ecs.store().getEntityCount(), "the store holds just the one body");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (T) ((Unsafe) f.get(null)).allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
