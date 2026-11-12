package com.alechilles.alecstamework.settings;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The claim mod Tamework's claim integration uses: claim limits, the breeding claim rule and the
 * claim mod's tamed damage rules.
 */
public enum ClaimModChoice {
    /** SimpleClaims when it is installed, otherwise QuestLines Claims when that is installed. */
    AUTOMATIC("Automatic"),
    SIMPLE_CLAIMS("SimpleClaims"),
    QUESTLINES_CLAIMS("QuestLinesClaims");

    private final String configValue;

    ClaimModChoice(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    /** The value saved in settings and asset files. */
    @Nonnull
    public String toConfigValue() {
        return configValue;
    }

    /**
     * Reads a saved value in any letter case, ignoring spaces, dashes and underscores. A missing
     * or unknown value, including the retired {@code Off}, gives {@link #AUTOMATIC}, so a settings
     * file written before this choice existed keeps its behaviour.
     */
    @Nonnull
    public static ClaimModChoice fromConfigValue(@Nullable String value) {
        if (value == null) {
            return AUTOMATIC;
        }
        String normalized = value.trim()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "simpleclaims" -> SIMPLE_CLAIMS;
            case "questlinesclaims", "questlines" -> QUESTLINES_CLAIMS;
            default -> AUTOMATIC;
        };
    }
}
