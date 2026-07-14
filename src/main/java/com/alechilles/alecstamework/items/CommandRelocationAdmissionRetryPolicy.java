package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionRelocationAdmissionService;
import java.util.Set;
import javax.annotation.Nullable;

/** Classifies optimistic population-admission conflicts that a pending relocation may retry. */
final class CommandRelocationAdmissionRetryPolicy {
    private static final Set<String> RETRYABLE_DENIALS = Set.of(
            "claim-occupancy-state-mismatch",
            "claim-profile-pending",
            "relocation-population-revision-mismatch",
            "relocation-population-state-unavailable",
            "relocation-source-projection-missing"
    );

    private CommandRelocationAdmissionRetryPolicy() {
    }

    static boolean shouldRetry(@Nullable CompanionRelocationAdmissionService.Decision decision) {
        if (decision == null) {
            return false;
        }
        if (decision.status() == CompanionRelocationAdmissionService.Status.CANCELED) {
            return true;
        }
        return decision.status() == CompanionRelocationAdmissionService.Status.DENIED
                && RETRYABLE_DENIALS.contains(decision.reason());
    }
}
