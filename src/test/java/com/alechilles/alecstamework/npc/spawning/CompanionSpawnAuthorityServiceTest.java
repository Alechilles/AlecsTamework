package com.alechilles.alecstamework.npc.spawning;

import com.hypixel.hytale.server.core.entity.reference.InvalidatablePersistentRef;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionSpawnAuthorityServiceTest {
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
}
