package com.alechilles.alecstamework.api;

/**
 * Public source buckets for companion XP awards emitted through the Tamework API event bus.
 *
 * @deprecated Retained as the source vocabulary for released legacy XP events and V2 outcomes.
 */
@Deprecated
public enum CompanionXpSource {
    FEED,
    HARVEST,
    BREEDING,
    COMBAT_DAMAGE_DEALT,
    COMBAT_DAMAGE_TAKEN,
    CUSTOM,
    AVATAR_FLIGHT,
    SUMMONED
}
