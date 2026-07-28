package com.alechilles.alecstamework.config.population;

import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import java.util.Set;
import javax.annotation.Nonnull;

/** Winning config metadata paired with its replacement-domain policy. */
public record PopulationGroupConfigDefinition(
        @Nonnull String configId,
        int priority,
        @Nonnull Set<String> roleIds,
        @Nonnull PopulationGroupPolicy policy
) {
    public PopulationGroupConfigDefinition {
        configId = requireText(configId, "Population group config ID");
        if (roleIds == null || roleIds.isEmpty() || policy == null) {
            throw new IllegalArgumentException(
                    "Complete population group definition is required"
            );
        }
        roleIds = Set.copyOf(roleIds);
        for (String roleId : roleIds) {
            requireText(roleId, "Population group role ID");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
