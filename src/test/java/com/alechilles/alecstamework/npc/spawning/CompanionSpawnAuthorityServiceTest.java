package com.alechilles.alecstamework.npc.spawning;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.reference.InvalidatablePersistentRef;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionSpawnAuthorityServiceTest {
    @Test
    void projectedCompanionDetachesWhenNpcIsNotLoadedWithMarker()
            throws Exception {
        UUID companionId = UUID.fromString(
                "00000000-0000-0000-0000-000000000033"
        );
        UUID wildNpcId = UUID.fromString(
                "00000000-0000-0000-0000-000000000044"
        );
        SpawnMarkerEntity marker = markerWithoutSpawningContext();
        marker.setNpcReferences(new InvalidatablePersistentRef[] {
                reference(companionId),
                reference(wildNpcId)
        });
        marker.setSpawnCount(2);
        ComponentType<EntityStore, SpawnMarkerEntity> markerType =
                new ComponentType<>();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                new ComponentType<>();

        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(new UnloadedEntityStore())) {
            Ref<EntityStore> markerRef = store.createReference();
            store.put(markerRef, markerType, marker);

            int detached =
                    CompanionSpawnAuthorityService.detachLoadedTamedMembers(
                            markerRef,
                            store,
                            markerType,
                            tamedType,
                            companionId::equals
                    );

            assertEquals(1, detached);
            assertEquals(1, marker.getSpawnCount());
            assertEquals(1, marker.getNpcReferences().length);
            assertEquals(wildNpcId, marker.getNpcReferences()[0].getUuid());
        }
    }

    @Test
    void detachingCompanionRemovesEveryReverseMarkerReference() throws Exception {
        UUID companionId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID wildNpcId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        SpawnMarkerEntity marker = markerWithoutSpawningContext();
        marker.setNpcReferences(new InvalidatablePersistentRef[] {
                reference(companionId),
                reference(wildNpcId),
                reference(companionId)
        });
        marker.setSpawnCount(3);

        int removed = CompanionSpawnAuthorityService.removeMarkerMember(
                marker,
                companionId
        );

        assertEquals(2, removed);
        assertEquals(1, marker.getSpawnCount());
        assertEquals(1, marker.getNpcReferences().length);
        assertEquals(wildNpcId, marker.getNpcReferences()[0].getUuid());
    }

    private static InvalidatablePersistentRef reference(UUID uuid) {
        InvalidatablePersistentRef reference = new InvalidatablePersistentRef();
        reference.setUuid(uuid);
        return reference;
    }

    private static SpawnMarkerEntity markerWithoutSpawningContext()
            throws Exception {
        Constructor<?> constructor = Arrays.stream(
                        SpawnMarkerEntity.class.getDeclaredConstructors()
                )
                .filter(candidate -> candidate.getParameterCount() == 1)
                .findFirst()
                .orElseThrow();
        constructor.setAccessible(true);
        return (SpawnMarkerEntity) constructor.newInstance(new Object[] { null });
    }

    private static final class UnloadedEntityStore extends EntityStore {
        private UnloadedEntityStore() {
            super(null);
        }

        @Override
        public Ref<EntityStore> getRefFromUUID(UUID uuid) {
            return null;
        }
    }
}
