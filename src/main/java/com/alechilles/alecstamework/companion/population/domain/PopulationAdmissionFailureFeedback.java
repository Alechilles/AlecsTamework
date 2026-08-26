package com.alechilles.alecstamework.companion.population.domain;

import javax.annotation.Nullable;

/** Formats admission failures that contain enough evidence for player feedback. */
public final class PopulationAdmissionFailureFeedback {
    private PopulationAdmissionFailureFeedback() {
    }

    /** Returns a clear player message, or {@code null} when the cause is unrelated. */
    @Nullable
    public static String describe(@Nullable Throwable failure, String action) {
        String safeAction = action == null || action.isBlank()
                ? "action" : action.trim();
        while (failure != null) {
            if (failure instanceof PopulationDomainCapacityException capacity) {
                String scope = capacity.status()
                        == PopulationDomainAdmission.Status.DEPLOYABLE_CAPACITY_REACHED
                        ? "deployed" : "owned";
                String slots = capacity.requestedUsage() == 1 ? "slot" : "slots";
                return "Not enough " + scope + " companion capacity: "
                        + capacity.currentUsage() + " / " + capacity.limit()
                        + " slots used; " + safeAction + " needs "
                        + capacity.requestedUsage() + " " + slots + ".";
            }
            String reason = failure.getMessage();
            if ("population_domain_deployable_capacity_reached".equals(reason)
                    || "runehusbandry.admission.deployable_limit".equals(reason)) {
                return "Your deployed companion limit has been reached.";
            }
            if ("population_domain_owned_capacity_reached".equals(reason)
                    || "runehusbandry.admission.owned_limit".equals(reason)) {
                return "Your owned companion limit has been reached.";
            }
            if ("runehusbandry.admission.family_locked".equals(reason)) {
                return "Your Husbandry level is too low to " + safeAction
                        + " this companion.";
            }
            if ("runehusbandry.admission.family_unknown".equals(reason)) {
                return "This companion family is not configured for Husbandry.";
            }
            if ("runehusbandry.admission.provider_unavailable".equals(reason)
                    || (reason != null
                    && reason.startsWith("provider-unavailable:"))) {
                return "Husbandry requirements are temporarily unavailable. "
                        + "Try again shortly.";
            }
            failure = failure.getCause();
        }
        return null;
    }
}
