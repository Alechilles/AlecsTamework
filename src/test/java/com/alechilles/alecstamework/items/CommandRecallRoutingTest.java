package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRecallRoutingTest {
    private static CompanionRecord liveIn(String world) {
        return CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(UUID.randomUUID(),
                UUID.randomUUID(), "Alec", "Tamed_Sheep", null, world, 0, 0, 0, List.of(), CompanionSummary.EMPTY));
    }

    @Test
    void eachCompanionStateGetsItsRecallRoute() {
        CompanionRecord here = liveIn("default");
        CompanionRecord dead = CompanionTransitions.died(here, CompanionSummary.EMPTY, 1L, 2L, "PLAYER", null)
                .apply(here.toBuilder()).build();

        assertEquals(RecallRoute.REFUSE, RecallRoute.decide(null, false, "default"));
        assertEquals(RecallRoute.MOVE_LOADED, RecallRoute.decide(here, true, "default"));
        assertEquals(RecallRoute.LOAD_AND_MOVE, RecallRoute.decide(here, false, "default"));
        assertEquals(RecallRoute.RESTORE, RecallRoute.decide(liveIn("other"), false, "default"));
        assertEquals(RecallRoute.REFUSE, RecallRoute.decide(dead, false, "default"));
    }
}
