package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.settings.AnimalAgingMode;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Preset profiles that can be loaded into the `/tw settings` form before applying changes.
 */
public enum TameworkSettingsPreset {
    CUSTOM("Custom", "tamework.ui.settings.preset.custom"),
    SIMPLIFIED("Simplified", "tamework.ui.settings.preset.simplified"),
    EASIER("Easier", "tamework.ui.settings.preset.easier"),
    FULL_EXPERIENCE("FullExperience", "tamework.ui.settings.preset.fullExperience"),
    HARDCORE("Hardcore", "tamework.ui.settings.preset.hardcore");

    private final String value;
    private final String displayKey;

    TameworkSettingsPreset(@Nonnull String value, @Nonnull String displayKey) {
        this.value = value;
        this.displayKey = displayKey;
    }

    @Nonnull
    public String value() {
        return value;
    }

    @Nonnull
    public String displayName() {
        return displayName(null);
    }

    @Nonnull
    public String displayName(@Nullable String language) {
        return LocalizedText.resolve(language, displayKey);
    }

    @Nonnull
    public String displayKey() {
        return displayKey;
    }

    public boolean isLoadable() {
        return this != CUSTOM;
    }

    @Nonnull
    public TameworkSettingsValues applyTo(@Nonnull TameworkSettingsValues values) {
        return switch (this) {
            case CUSTOM -> values;
            case SIMPLIFIED -> values.withExperienceSettings(false, false, false, false, false, false, false, false, false, false, false, AnimalAgingMode.OFF);
            case EASIER -> values.withExperienceSettings(true, false, false, true, true, true, true, true, true, true, false, AnimalAgingMode.FREEZE_AT_PRIME);
            case FULL_EXPERIENCE -> values.withExperienceSettings(true, true, true, true, true, true, true, true, true, true, false, AnimalAgingMode.FULL);
            case HARDCORE -> values.withExperienceSettings(true, true, true, true, true, true, true, true, true, true, true, AnimalAgingMode.FULL);
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
        for (TameworkSettingsPreset preset : values()) {
            if (preset.isLoadable() && preset.applyTo(values).equals(values)) {
                return preset;
            }
        }
        return CUSTOM;
    }

    @Nonnull
    public static List<DropdownEntryInfo> dropdownEntries() {
        return dropdownEntries(null);
    }

    @Nonnull
    public static List<DropdownEntryInfo> dropdownEntries(@Nullable String language) {
        return List.of(
                new DropdownEntryInfo(LocalizableString.fromString(SIMPLIFIED.displayName(language)), SIMPLIFIED.value),
                new DropdownEntryInfo(LocalizableString.fromString(EASIER.displayName(language)), EASIER.value),
                new DropdownEntryInfo(LocalizableString.fromString(FULL_EXPERIENCE.displayName(language)), FULL_EXPERIENCE.value),
                new DropdownEntryInfo(LocalizableString.fromString(HARDCORE.displayName(language)), HARDCORE.value),
                new DropdownEntryInfo(LocalizableString.fromString(CUSTOM.displayName(language)), CUSTOM.value)
        );
    }

}
