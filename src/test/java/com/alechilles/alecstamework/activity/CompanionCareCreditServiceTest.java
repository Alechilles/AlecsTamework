package com.alechilles.alecstamework.activity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Behavior checks for the shared manual/autonomous care-credit gate. */
class CompanionCareCreditServiceTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");

    @Test
    void grantsOneCreditPerCompanionDuringTheCooldownWindow() {
        AtomicBoolean creditAvailable = new AtomicBoolean(true);
        CompanionCareCreditService service = new CompanionCareCreditService(
                (companionId, ownerId) -> companionId.equals(COMPANION)
                        && ownerId.equals(OWNER)
                        && creditAvailable.compareAndSet(true, false)
        );

        assertTrue(service.tryAcquire(COMPANION, OWNER));
        assertFalse(service.tryAcquire(COMPANION, OWNER));
    }
}
