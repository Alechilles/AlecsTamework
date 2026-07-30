package com.alechilles.alecstamework.config.assets;

import javax.annotation.Nullable;

/** Named combat-ability slots available while avatar flight is active. */
public enum AvatarFlightCombatAbilitySlot {
    ABILITY_2("Ability2"),
    ABILITY_3("Ability3");

    private final String serializedKey;

    AvatarFlightCombatAbilitySlot(String serializedKey) {
        this.serializedKey = serializedKey;
    }

    public String getSerializedKey() {
        return serializedKey;
    }

    @Nullable
    public static AvatarFlightCombatAbilitySlot fromSerializedKey(@Nullable String serializedKey) {
        if (serializedKey == null) return null;
        for (AvatarFlightCombatAbilitySlot slot : values()) {
            if (slot.serializedKey.equals(serializedKey)) return slot;
        }
        return null;
    }
}
