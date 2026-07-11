package com.alechilles.alecstamework.integration.claims;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime provider selection for Tamework claim-aware population limits.
 */
public enum ClaimIntegrationProvider {
    AUTO("Auto"),
    SIMPLE_CLAIMS("SimpleClaims"),
    QUESTLINES_CLAIMS("QuestLinesClaims"),
    OFF("Off");

    private final String configValue;

    ClaimIntegrationProvider(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    @Nonnull
    public String configValue() {
        return configValue;
    }

    @Nonnull
    public static ClaimIntegrationProvider fromConfigValue(@Nullable String raw) {
        ClaimIntegrationProvider parsed = tryFromConfigValue(raw);
        return parsed == null ? AUTO : parsed;
    }

    /**
     * Parses a configured provider without silently converting an invalid explicit value to
     * {@link #AUTO}. A missing or blank value remains the documented Auto default.
     *
     * @return the parsed provider, or {@code null} when {@code raw} is an unrecognized value
     */
    @Nullable
    public static ClaimIntegrationProvider tryFromConfigValue(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        String normalized = raw.trim()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "simpleclaims", "simpleclaim" -> SIMPLE_CLAIMS;
            case "questlinesclaims", "questlineclaims", "qlclaims", "qlc" -> QUESTLINES_CLAIMS;
            case "off", "disabled", "none", "false" -> OFF;
            case "auto", "automatic", "default" -> AUTO;
            default -> null;
        };
    }
}
