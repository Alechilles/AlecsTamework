package com.alechilles.alecstamework.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Behavior checks for bounded world-thread defeat credit. */
class CompanionCombatContributionLedgerTest {
    private static final UUID TARGET = UUID.fromString(
            "10000000-0000-0000-0000-000000000802");
    private static final UUID COMPANION_A = UUID.fromString(
            "20000000-0000-0000-0000-000000000802");
    private static final UUID COMPANION_B = UUID.fromString(
            "30000000-0000-0000-0000-000000000802");
    private static final UUID OWNER_A = UUID.fromString(
            "40000000-0000-0000-0000-000000000802");
    private static final UUID OWNER_B = UUID.fromString(
            "50000000-0000-0000-0000-000000000802");

    @Test
    void removalReturnsTotalsFinalBlowAndOwnerCreditOnce() {
        CompanionCombatContributionLedger ledger =
                new CompanionCombatContributionLedger(8, 1_000L, 4);
        ledger.record(
                UUID.randomUUID(), TARGET, COMPANION_A, OWNER_A,
                3.0, 100L);
        UUID finalOperation = UUID.randomUUID();
        ledger.record(
                finalOperation, TARGET, COMPANION_B, OWNER_B,
                5.0, 200L);
        ledger.record(
                finalOperation, TARGET, COMPANION_A, OWNER_A,
                2.0, 200L);

        CompanionCombatContributionLedger.DefeatCredit credit =
                ledger.remove(TARGET, 250L).orElseThrow();

        assertEquals(finalOperation, credit.operationId());
        assertEquals(COMPANION_A, credit.finalBlowCredit().companionId());
        assertEquals(OWNER_A, credit.ownerCredit());
        assertEquals(2, credit.contributors().size());
        assertEquals(5.0, credit.contributors().stream()
                .filter(value -> value.companionId().equals(COMPANION_A))
                .findFirst().orElseThrow().contribution());
        assertFalse(ledger.remove(TARGET, 250L).isPresent());
    }

    @Test
    void lazyExpiryCapAndClearKeepStateBounded() {
        CompanionCombatContributionLedger ledger =
                new CompanionCombatContributionLedger(2, 100L, 4);
        UUID targetB = UUID.randomUUID();
        UUID targetC = UUID.randomUUID();
        ledger.record(UUID.randomUUID(), TARGET, COMPANION_A, OWNER_A,
                1.0, 0L);
        ledger.record(UUID.randomUUID(), targetB, COMPANION_A, OWNER_A,
                1.0, 50L);
        ledger.record(UUID.randomUUID(), targetC, COMPANION_A, OWNER_A,
                1.0, 75L);

        assertEquals(2, ledger.size());
        assertFalse(ledger.remove(TARGET, 75L).isPresent());
        ledger.record(UUID.randomUUID(), UUID.randomUUID(),
                COMPANION_B, OWNER_B, 1.0, 200L);
        assertTrue(ledger.remove(targetB, 200L).isEmpty());

        ledger.clear();
        assertEquals(0, ledger.size());
    }
}
