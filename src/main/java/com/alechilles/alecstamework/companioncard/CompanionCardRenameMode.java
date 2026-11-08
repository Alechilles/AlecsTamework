package com.alechilles.alecstamework.companioncard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Whether, and at what cost, the companion card's rename pencil renames a companion. */
public enum CompanionCardRenameMode {
    /** Renames with the default naming rules; nothing is consumed. */
    FREE("FREE"),
    /** Needs a naming item (any item with a {@code TwNameItemConfig}) in the inventory and uses its rules. */
    ITEM("ITEM"),
    /** The card shows no rename pencil. */
    OFF("OFF");

    private final String configValue;

    CompanionCardRenameMode(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    @Nonnull
    public String toConfigValue() {
        return configValue;
    }

    @Nonnull
    public static CompanionCardRenameMode fromConfigValue(@Nullable String value) {
        if (value != null) {
            for (CompanionCardRenameMode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return ITEM;
    }
}
