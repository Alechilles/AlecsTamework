package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures progression/effect failures cannot strand a live offspring admission. */
class BreedingLiveChildCompletionTest {
    @Test
    void throwingProgressionAndEffectsStillCommitAndReleaseNearbyUnit() {
        AtomicBoolean laterStageRan = new AtomicBoolean();
        AtomicBoolean commitStarted = new AtomicBoolean();
        AtomicBoolean nearbyReleased = new AtomicBoolean();
        List<String> degraded = new ArrayList<>();

        new BreedingLiveChildCompletion().finish(
                List.of(
                        new BreedingLiveChildCompletion.SideEffect(
                                "breeding-progression-failed",
                                () -> {
                                    throw new IllegalStateException("progression failure");
                                }
                        ),
                        new BreedingLiveChildCompletion.SideEffect(
                                "breeding-effects-failed",
                                () -> {
                                    throw new IllegalStateException("effect failure");
                                }
                        ),
                        new BreedingLiveChildCompletion.SideEffect(
                                "breeding-later-stage-failed",
                                () -> laterStageRan.set(true)
                        )
                ),
                () -> {
                    commitStarted.set(true);
                    return CompletableFuture.completedFuture(
                            new CompanionPopulationCommitResult(
                                    true, "breeding-population-committed", true, null
                            )
                    );
                },
                degraded::add,
                () -> nearbyReleased.set(true)
        );

        assertTrue(laterStageRan.get());
        assertTrue(commitStarted.get());
        assertTrue(nearbyReleased.get());
        assertEquals(
                List.of("breeding-progression-failed", "breeding-effects-failed"),
                degraded
        );
    }
}
