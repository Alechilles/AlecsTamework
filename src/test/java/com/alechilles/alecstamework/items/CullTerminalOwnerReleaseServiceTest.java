package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CullTerminalOwnerReleaseServiceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final LoadedBodies<Ref<EntityStore>> loaded = new LoadedBodies<>();
    private final CullTerminalOwnerReleaseService.Port port = CullTerminalOwnerReleaseService.from(
            new ReleaseFlow(index, loaded, id -> { }),
            new CompanionQueries(index, loaded));

    private UUID insertCompanion(UUID npcUuid) {
        UUID profile = UUID.randomUUID();
        index.insert(CompanionTransitions.newLive(profile, 0, new CompanionTransitions.BodyFacts(npcUuid, OWNER,
                "Alec", "Tamed_Sheep", null, "default", 0, 0, 0, List.of(), CompanionSummary.EMPTY)));
        return profile;
    }

    @Test
    void anNpcWithoutARecordIsNotTrackedSoItCanBeCulledAsWild() {
        assertEquals(CullTerminalOwnerReleaseService.Outcome.NOT_TRACKED,
                port.release(OWNER, UUID.randomUUID()).toCompletableFuture().join());
    }

    @Test
    void theOwnersCompanionIsReleased() {
        UUID npc = UUID.randomUUID();
        UUID profile = insertCompanion(npc);

        assertEquals(CullTerminalOwnerReleaseService.Outcome.RELEASED,
                port.release(OWNER, npc).toCompletableFuture().join());
        assertEquals(LocationKind.RELEASED, index.get(profile).location().kind());
    }

    @Test
    void anotherPlayersCompanionIsNotReleased() {
        UUID npc = UUID.randomUUID();
        UUID profile = insertCompanion(npc);

        assertEquals(CullTerminalOwnerReleaseService.Outcome.UNAVAILABLE,
                port.release(UUID.randomUUID(), npc).toCompletableFuture().join());
        assertEquals(LocationKind.LIVE, index.get(profile).location().kind());
    }
}
