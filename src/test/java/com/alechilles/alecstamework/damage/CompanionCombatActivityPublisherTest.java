package com.alechilles.alecstamework.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.CombatDamageActivityView;
import com.alechilles.alecstamework.api.CombatDefeatActivityView;
import com.alechilles.alecstamework.api.CombatParticipantView;
import com.alechilles.alecstamework.api.CompanionXpOutcomeView;
import com.alechilles.alecstamework.api.CompanionXpSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Behavior checks for compact combat activity payloads. */
class CompanionCombatActivityPublisherTest {
    private static final UUID OPERATION = UUID.fromString(
            "10000000-0000-0000-0000-000000000801");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000801");
    private static final UUID TARGET = UUID.fromString(
            "30000000-0000-0000-0000-000000000801");
    private static final UUID OWNER = UUID.fromString(
            "40000000-0000-0000-0000-000000000801");

    @Test
    void damagePublishesOnePacketWithZeroOneOrTwoXpOutcomes() {
        List<ActivityView> published = new ArrayList<>();
        CompanionCombatActivityPublisher publisher =
                new CompanionCombatActivityPublisher(published::add);
        CombatParticipantView source =
                new CombatParticipantView(SOURCE, OWNER);
        CombatParticipantView target =
                new CombatParticipantView(TARGET, null);
        CompanionXpOutcomeView dealt = new CompanionXpOutcomeView(
                SOURCE, OWNER, CompanionXpSource.COMBAT_DAMAGE_DEALT, 3.0);
        CompanionXpOutcomeView taken = new CompanionXpOutcomeView(
                TARGET, null, CompanionXpSource.COMBAT_DAMAGE_TAKEN, 2.0);

        publisher.publishDamage(
                OPERATION, source, target, 6.0, "Physical",
                null, null, -3_000L);
        publisher.publishDamage(
                UUID.randomUUID(), source, target, 6.0, "Physical",
                dealt, null, -2_000L);
        publisher.publishDamage(
                UUID.randomUUID(), source, target, 6.0, "Physical",
                dealt, taken, -1_000L);

        assertEquals(3, published.size());
        CombatDamageActivityView zero =
                (CombatDamageActivityView) published.get(0);
        CombatDamageActivityView one =
                (CombatDamageActivityView) published.get(1);
        CombatDamageActivityView two =
                (CombatDamageActivityView) published.get(2);
        assertNull(zero.sourceXpOutcome());
        assertNull(zero.targetXpOutcome());
        assertEquals(dealt, one.sourceXpOutcome());
        assertNull(one.targetXpOutcome());
        assertEquals(dealt, two.sourceXpOutcome());
        assertEquals(taken, two.targetXpOutcome());
        assertEquals(Instant.ofEpochMilli(-3_000L),
                zero.header().occurredAt());
        assertEquals(ActivityIds.COMBAT_DAMAGE, zero.header().actionId());
    }

    @Test
    void defeatPublishesRemovedLedgerCredit() {
        List<ActivityView> published = new ArrayList<>();
        CompanionCombatActivityPublisher publisher =
                new CompanionCombatActivityPublisher(published::add);
        var contribution = new com.alechilles.alecstamework.api
                .CombatContributionView(SOURCE, OWNER, 9.0);

        publisher.publishDefeat(
                OPERATION,
                new CombatParticipantView(TARGET, null),
                contribution,
                List.of(contribution),
                OWNER,
                -1_000L);

        CombatDefeatActivityView defeat =
                (CombatDefeatActivityView) published.getFirst();
        assertEquals(ActivityIds.COMBAT_DEFEAT,
                defeat.header().actionId());
        assertEquals(contribution, defeat.finalBlowCredit());
        assertEquals(List.of(contribution), defeat.contributors());
        assertEquals(OWNER, defeat.ownerCredit());
    }
}
