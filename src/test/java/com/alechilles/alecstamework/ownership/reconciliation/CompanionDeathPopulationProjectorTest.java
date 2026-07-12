package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionDeathPopulationProjectorTest {
    @Test
    void revivableOwnedDeathRetainsOwnerAndReleasesPhysicalOccupancyImmediately() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Projection> projection = new AtomicReference<>();
        CompanionDeathPopulationProjector projector = new CompanionDeathPopulationProjector(
                (npc, owner, world, lifecycle, source) -> {
                    calls.incrementAndGet();
                    projection.set(new Projection(npc, owner, world, lifecycle, source));
                }
        );

        boolean observed = projector.observeRevivableDeath(
                new CompanionPopulationEntityObservation(npcUuid, ownerUuid, "Orbis", 4, -2),
                true
        );

        assertTrue(observed);
        assertEquals(1, calls.get());
        assertEquals(new Projection(
                npcUuid,
                ownerUuid,
                "Orbis",
                CompanionLifecycleState.DEAD_REVIVABLE,
                "ecs-death-component"
        ), projection.get());
    }

    @Test
    void unownedOrPermanentDeathDoesNotCreateDormantOwnership() {
        AtomicInteger calls = new AtomicInteger();
        CompanionDeathPopulationProjector projector = new CompanionDeathPopulationProjector(
                (npc, owner, world, lifecycle, source) -> calls.incrementAndGet()
        );
        UUID npcUuid = UUID.randomUUID();

        assertFalse(projector.observeRevivableDeath(
                new CompanionPopulationEntityObservation(npcUuid, null, "Orbis", 0, 0),
                true
        ));
        assertFalse(projector.observeRevivableDeath(
                new CompanionPopulationEntityObservation(
                        npcUuid, UUID.randomUUID(), "Orbis", 0, 0
                ),
                false
        ));
        assertEquals(0, calls.get());
    }

    private record Projection(
            UUID npcUuid,
            UUID ownerUuid,
            String worldName,
            CompanionLifecycleState lifecycle,
            String source
    ) {
    }
}
