package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for signed parent cooldown window construction. */
class BreedingCooldownServiceTest {
    @Test
    void negativeWorldCooldownPreservesSignedStartAndDeadline() {
        BreedingCooldownService.CooldownWindow window =
                BreedingCooldownService.resolveWindow(-3_000L, 2_000L);

        assertEquals(-1_000L, window.untilMs());
        assertEquals(-3_000L, window.startedAtMs());
        assertEquals(2_000L, window.durationMs());
    }

    @Test
    void realCooldownAvoidsZeroSentinelAndSaturatesOverflow() {
        assertEquals(1L, BreedingCooldownService.resolveWindow(-1_000L, 1_000L).untilMs());
        assertEquals(
                Long.MAX_VALUE,
                BreedingCooldownService.resolveWindow(Long.MAX_VALUE - 5L, 10L).untilMs()
        );
    }
}
