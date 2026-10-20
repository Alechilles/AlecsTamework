package com.alechilles.alecstamework.companion.flow;

/**
 * Why a companion died. The constant names are stable: {@link CompanionDeathTiming.Timing#cause()}
 * writes them into the companion record.
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
