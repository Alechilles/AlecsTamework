package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Preset profiles that can be loaded into the `/tw settings` form before applying changes.
 */
public enum TameworkSettingsPreset {
    CUSTOM("Custom", "Custom"),
    SIMPLIFIED("Simplified", "Simplified (Minecraft-like)"),
    EASIER("Easier", "Easier"),
    FULL_EXPERIENCE("FullExperience", "Full Experience");

    private final String value;
    private final String displayName;

    TameworkSettingsPreset(@Nonnull String value, @Nonnull String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    @Nonnull
    public String value() {
        return value;
    }

    @Nonnull
    public String displayName() {
        return displayName;
    }

    public boolean isLoadable() {
        return this != CUSTOM;
    }

    @Nonnull
    public TameworkSettingsValues applyTo(@Nonnull TameworkSettingsValues values) {
        return switch (this) {
            case CUSTOM -> values;
            case SIMPLIFIED -> values.withExperienceSettings(false, false, false, false, false, false, false);
            case EASIER -> values.withExperienceSettings(true, false, false, true, true, true, true);
            case FULL_EXPERIENCE -> values.withExperienceSettings(true, true, true, true, true, true, true);
        };
    }

    @Nonnull
    public static TameworkSettingsPreset fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return CUSTOM;
        }
        for (TameworkSettingsPreset preset : values()) {
            if (preset.value.equalsIgnoreCase(value.trim())) {
                return preset;
            }
        }
        return CUSTOM;
    }

    @Nonnull
    public static TameworkSettingsPreset match(@Nonnull TameworkSettingsValues values) {
        if (matches(values, false, false, false, false, false, false, false)) {
            return SIMPLIFIED;
        }
        if (matches(values, true, false, false, true, true, true, true)) {
            return EASIER;
        }
        if (matches(values, true, true, true, true, true, true, true)) {
            return FULL_EXPERIENCE;
        }
        return CUSTOM;
    }

    @Nonnull
    public static List<DropdownEntryInfo> dropdownEntries() {
        return List.of(
                new DropdownEntryInfo(LocalizableString.fromString(SIMPLIFIED.displayName), SIMPLIFIED.value),
                new DropdownEntryInfo(LocalizableString.fromString(EASIER.displayName), EASIER.value),
                new DropdownEntryInfo(LocalizableString.fromString(FULL_EXPERIENCE.displayName), FULL_EXPERIENCE.value),
                new DropdownEntryInfo(LocalizableString.fromString(CUSTOM.displayName), CUSTOM.value)
        );
    }

    private static boolean matches(@Nonnull TameworkSettingsValues values,
                                   boolean needsEnabled,
                                   boolean needsDamageEnabled,
                                   boolean needsDamageLethal,
                                   boolean happinessEnabled,
                                   boolean passiveBreedingEnabled,
                                   boolean breedingRequiresHappiness,
                                   boolean traitsEnabled) {
        return values.needsEnabled() == needsEnabled
                && values.needsDamageEnabled() == needsDamageEnabled
                && values.needsDamageLethal() == needsDamageLethal
                && values.happinessEnabled() == happinessEnabled
                && values.passiveBreedingEnabled() == passiveBreedingEnabled
                && values.breedingRequiresHappiness() == breedingRequiresHappiness
                && values.traitsEnabled() == traitsEnabled;
    }
}
