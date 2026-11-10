package com.alechilles.alecstamework.companioncard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompanionCardRequestsTest {
    private final UUID npc = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    @AfterEach
    void clear() {
        CompanionCardRequests.clear();
    }

    @Test
    void backgroundRefreshDoesNotDropAWaitingClick() {
        CompanionCardRequests.SetMode click = new CompanionCardRequests.SetMode("cfg", 2);
        CompanionCardRequests.queue(npc, player, click);
        CompanionCardRequests.queue(npc, player, new CompanionCardRequests.Refresh());

        assertEquals(click, CompanionCardRequests.take(npc, player));
    }

    @Test
    void newerClickStillReplacesAnOlderOne() {
        CompanionCardRequests.queue(npc, player, new CompanionCardRequests.SetMode("cfg", 1));
        CompanionCardRequests.RunEntry latest = new CompanionCardRequests.RunEntry("cfg", 4);
        CompanionCardRequests.queue(npc, player, latest);

        assertEquals(latest, CompanionCardRequests.take(npc, player));
    }
}
