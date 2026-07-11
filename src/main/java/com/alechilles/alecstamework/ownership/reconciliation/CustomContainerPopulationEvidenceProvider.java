package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Extension SPI for mods whose persisted containers are not represented by base ItemContainerBlock
 * holders or player inventory components.
 */
public interface CustomContainerPopulationEvidenceProvider {
    @Nonnull
    String providerId();

    @Nonnull
    CompanionPopulationEvidenceSource createEvidenceSource() throws Exception;

    @Nonnull
    static String normalizeProviderId(@Nonnull String value) {
        String normalized = Objects.requireNonNull(value, "providerId").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("providerId must not be blank.");
        }
        return normalized;
    }
}
