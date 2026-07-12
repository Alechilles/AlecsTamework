package com.alechilles.alecstamework.ownership;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for asynchronous legacy-adoption result semantics. */
class LegacyTamedOwnershipBridgeResultTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void scheduledAdoptionDoesNotExposeOrReportTheRequestedOwnerAsApplied() {
        LegacyTamedOwnershipBridge.ClaimResult scheduled =
                LegacyTamedOwnershipBridge.ClaimResult.scheduled();

        assertTrue(scheduled.isScheduled());
        assertFalse(scheduled.isClaimed());
        assertNull(scheduled.getOwnerId());
    }

    @Test
    void appliedCallbackResultReportsTheCommittedLiveOwner() {
        LegacyTamedOwnershipBridge.ClaimResult claimed =
                LegacyTamedOwnershipBridge.ClaimResult.claimed(OWNER, "Owner A");

        assertTrue(claimed.isClaimed());
        assertFalse(claimed.isScheduled());
        assertEquals(OWNER, claimed.getOwnerId());
        assertEquals("Owner A", claimed.getOwnerName());
    }

    @Test
    void deniedAdoptionCarriesReasonWithoutPretendingAnOwnerExists() {
        LegacyTamedOwnershipBridge.ClaimResult denied =
                LegacyTamedOwnershipBridge.ClaimResult.denied("claim-cap-reached");

        assertTrue(denied.isDenied());
        assertFalse(denied.isClaimed());
        assertNull(denied.getOwnerId());
        assertEquals("claim-cap-reached", denied.getReason());
    }
}
