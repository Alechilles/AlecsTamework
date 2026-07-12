package com.alechilles.alecstamework.ownership.reconciliation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLiveEvidenceRevisionTest {
    @Test
    void playerInventoryEventAdvancesTheSharedFence() {
        CompanionLiveEvidenceRevision revision = new CompanionLiveEvidenceRevision();
        CompanionLiveInventoryEvidenceSystem system =
                new CompanionLiveInventoryEvidenceSystem(revision);
        long baseline = revision.capture();

        system.handle(0, null, null, null, null);

        assertFalse(revision.isCurrent(baseline));
        assertTrue(revision.isCurrent(baseline + 1L));
    }
}
