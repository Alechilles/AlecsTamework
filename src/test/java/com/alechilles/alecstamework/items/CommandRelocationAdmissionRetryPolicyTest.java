package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionRelocationAdmissionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for single-request recovery from live reconciliation races. */
class CommandRelocationAdmissionRetryPolicyTest {

    @Test
    void retriesCanceledCapabilitiesAndOptimisticOccupancyConflicts() {
        assertTrue(retryable(
                CompanionRelocationAdmissionService.Status.CANCELED,
                "population-admission-context-or-reservation-invalid"
        ));
        assertTrue(retryable(
                CompanionRelocationAdmissionService.Status.DENIED,
                "claim-occupancy-state-mismatch"
        ));
        assertTrue(retryable(
                CompanionRelocationAdmissionService.Status.DENIED,
                "claim-profile-pending"
        ));
        assertTrue(retryable(
                CompanionRelocationAdmissionService.Status.DENIED,
                "relocation-population-revision-mismatch"
        ));
    }

    @Test
    void persistenceDegradationAndOwnershipDenialsRemainTerminal() {
        assertFalse(retryable(
                CompanionRelocationAdmissionService.Status.DEGRADED,
                "owner-population-persistence-degraded"
        ));
        assertFalse(retryable(
                CompanionRelocationAdmissionService.Status.DENIED,
                "relocation-owner-mismatch"
        ));
    }

    private static boolean retryable(CompanionRelocationAdmissionService.Status status,
                                     String reason) {
        return CommandRelocationAdmissionRetryPolicy.shouldRetry(
                new CompanionRelocationAdmissionService.Decision(status, reason, null)
        );
    }
}
