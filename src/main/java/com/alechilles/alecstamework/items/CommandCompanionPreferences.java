package com.alechilles.alecstamework.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Set;

/** Per-flute presentation preferences, deliberately independent from recipient membership. */
final class CommandCompanionPreferences {
    private static final String STATE = "Tamework.Command.CompanionState";
    private static final String NEARBY = "Tamework.Command.CompanionNearby";
    private static final Set<String> STATES = Set.of("InWorld", "Stored", "LostDead", "All");
    static String state(ItemStack stack) {
        String value = stack == null ? null : stack.getFromMetadataOrNull(STATE, Codec.STRING);
        return value != null && STATES.contains(value) ? value : "InWorld";
    }
    static boolean nearby(ItemStack stack) { return stack != null && Boolean.TRUE.equals(stack.getFromMetadataOrNull(NEARBY, Codec.BOOLEAN)); }
    static ItemStack state(ItemStack stack, String value) {
        return stack == null || value == null || !STATES.contains(value) ? stack : stack.withMetadata(STATE, Codec.STRING, value);
    }
    static ItemStack nearby(ItemStack stack, boolean value) { return stack == null ? null : stack.withMetadata(NEARBY, Codec.BOOLEAN, value); }
}
