package com.alechilles.alecstamework.npc.actions;

/**
 * Wall-clock time source for player-facing manual breeding selection windows.
 */
final class ManualBreedingClock {
    private ManualBreedingClock() {
    }

    static long nowMs() {
        return System.currentTimeMillis();
    }
}
