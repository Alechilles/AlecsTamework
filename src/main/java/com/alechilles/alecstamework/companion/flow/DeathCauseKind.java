package com.alechilles.alecstamework.companion.flow;

/**
 * Stable death-cause identifiers.
 *
 * <p>{@link CompanionDeathTiming} maps a needs cause to its own {@code Kind} by constant name, so
 * the names must match there.</p>
 */
public enum DeathCauseKind {
    STARVATION,
    DEHYDRATION,
    STARVATION_AND_DEHYDRATION,
    PLAYER,
    NPC,
    ENVIRONMENT,
    UNKNOWN
}
