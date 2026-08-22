package com.alechilles.alecstamework.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.RevivalActivityView;
import com.alechilles.alecstamework.api.SummoningActivityView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Behavior checks for post-commit lifecycle activity payloads. */
class LifecycleActivityPublisherTest {
    private static final UUID OPERATION = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");

    @Test
    void publishesRevivalAndSummoningFromCommittedEvidence() {
        List<ActivityView> published = new ArrayList<>();
        LifecycleActivityPublisher publisher = new LifecycleActivityPublisher(
                published::add);

        publisher.publishRevival(
                OPERATION, OWNER, OWNER, COMPANION, "profile-a",
                "paid_command", "active", "settled", true);
        publisher.publishSummoning(
                OPERATION, ActivityIds.SUMMON_SUCCESS, OWNER, "profile-a",
                "family-a", COMPANION, "summon_started", 5_000L);
        publisher.publishSummoning(
                UUID.randomUUID(), ActivityIds.RECALL, OWNER, "profile-a",
                "family-a", COMPANION, "stored", null);

        RevivalActivityView revival = assertInstanceOf(
                RevivalActivityView.class, published.get(0));
        assertEquals(OPERATION, revival.header().operationId());
        assertEquals(ActivityIds.REVIVE_SUCCESS, revival.header().actionId());
        assertEquals("paid_command", revival.revivalSource());
        assertEquals("settled", revival.paymentOutcome());
        assertTrue(revival.recovered());

        SummoningActivityView summon = assertInstanceOf(
                SummoningActivityView.class, published.get(1));
        assertEquals(ActivityIds.SUMMON_SUCCESS, summon.header().actionId());
        assertEquals(5_000L, summon.expiresAtMs());
        SummoningActivityView recall = assertInstanceOf(
                SummoningActivityView.class, published.get(2));
        assertEquals(ActivityIds.RECALL, recall.header().actionId());
        assertNull(recall.expiresAtMs());
    }
}
