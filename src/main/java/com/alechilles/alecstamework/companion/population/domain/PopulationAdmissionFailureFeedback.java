package com.alechilles.alecstamework.companion.population.domain;

import javax.annotation.Nullable;
import com.alechilles.alecstamework.localization.LocalizedText;

/** Formats admission failures that contain enough evidence for player feedback. */
public final class PopulationAdmissionFailureFeedback {
    private PopulationAdmissionFailureFeedback() {
    }

    /** Returns a clear player message, or {@code null} when the cause is unrelated. */
    @Nullable
    public static String describe(@Nullable Throwable failure, String action) {
        return describe(failure, action, null);
    }

    /** Resolves player feedback in the recipient's language; null uses the default locale. */
    @Nullable
    public static String describe(@Nullable Throwable failure, String action, @Nullable String language) {
        String actionKey = switch (action == null ? "" : action.trim()) {
            case "capture", "release", "revive" -> action.trim();
            default -> "default";
        };
        String safeAction = LocalizedText.resolve(language, "tamework.ui.population.action." + actionKey);
        while (failure != null) {
            if (failure instanceof PopulationDomainCapacityException capacity) {
                String scope = capacity.status()
                        == PopulationDomainAdmission.Status.DEPLOYABLE_CAPACITY_REACHED
                        ? "deployed" : "owned";
                String slots = LocalizedText.resolve(language, "tamework.ui.population."
                        + (capacity.requestedUsage() == 1 ? "slot" : "slots"));
                return LocalizedText.format(language, "tamework.ui.population.capacity." + scope,
                        capacity.currentUsage(), capacity.limit(), safeAction, capacity.requestedUsage(), slots);
            }
            String reason = failure.getMessage();
            if ("population_domain_deployable_capacity_reached".equals(reason)
                    || "runehusbandry.admission.deployable_limit".equals(reason)) {
                return LocalizedText.resolve(language, "tamework.ui.population.deployedLimit");
            }
            if ("population_domain_owned_capacity_reached".equals(reason)
                    || "runehusbandry.admission.owned_limit".equals(reason)) {
                return LocalizedText.resolve(language, "tamework.ui.population.ownedLimit");
            }
            if ("runehusbandry.admission.family_locked".equals(reason)) {
                return LocalizedText.format(language, "tamework.ui.population.familyLocked", safeAction);
            }
            if ("runehusbandry.admission.family_unknown".equals(reason)) {
                return LocalizedText.resolve(language, "tamework.ui.population.familyUnknown");
            }
            if ("runehusbandry.admission.provider_unavailable".equals(reason)
                    || (reason != null
                    && reason.startsWith("provider-unavailable:"))) {
                return LocalizedText.resolve(language, "tamework.ui.population.providerUnavailable");
            }
            failure = failure.getCause();
        }
        return null;
    }
}
