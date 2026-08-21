package com.alechilles.alecstamework.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Immutable domain and action selection for one Activity API V2 consumer. */
public record ActivityFilter(
        @Nonnull Set<ActivityDomain> domains,
        @Nonnull Set<String> actionIds
) {
    public ActivityFilter {
        Set<ActivityDomain> sourceDomains =
                Objects.requireNonNull(domains, "domains");
        if (sourceDomains.isEmpty()) {
            throw new IllegalArgumentException("At least one domain is required.");
        }
        EnumSet<ActivityDomain> normalizedDomains =
                EnumSet.noneOf(ActivityDomain.class);
        for (ActivityDomain domain : sourceDomains) {
            normalizedDomains.add(Objects.requireNonNull(domain, "domain"));
        }
        domains = Set.copyOf(normalizedDomains);

        Set<String> sourceActions =
                Objects.requireNonNull(actionIds, "actionIds");
        java.util.LinkedHashSet<String> normalizedActions =
                new java.util.LinkedHashSet<>();
        for (String actionId : sourceActions) {
            String normalized = ActivityHeader.requireNamespacedText(
                    actionId, "actionId");
            if (!normalizedActions.add(normalized)) {
                throw new IllegalArgumentException(
                        "actionIds contains duplicate identifier: " + normalized);
            }
        }
        actionIds = Set.copyOf(normalizedActions);
    }

    /** Returns a wildcard filter for one domain. */
    @Nonnull
    public static ActivityFilter forDomain(@Nonnull ActivityDomain domain) {
        return new ActivityFilter(Set.of(domain), Set.of());
    }

    /** Returns a filter that selects all V2 domains and actions. */
    @Nonnull
    public static ActivityFilter all() {
        return new ActivityFilter(EnumSet.allOf(ActivityDomain.class), Set.of());
    }

    /** Returns true when the activity is selected by this filter. */
    public boolean matches(ActivityDomain domain, String actionId) {
        return domains.contains(domain)
                && (actionIds.isEmpty() || actionIds.contains(actionId));
    }
}
