package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for retrying only missing deterministic litter members. */
class BreedingBirthPlanReplayServiceTest {
    @Test
    void persistedPartialLitterSkipsCommittedChildWithoutRerolling() {
        BreedingBirthPlan fullPlan = plan();
        BreedingBirthPlanSnapshot snapshot = new BreedingBirthPlanSnapshotMapper().snapshot(fullPlan);
        AtomicInteger rerolls = new AtomicInteger();

        BreedingBirthPlanReplayService.Resolution resolution =
                new BreedingBirthPlanReplayService().resolve(
                        new BreedingPopulationReplayState(
                                true, snapshot, Set.of("child-0"), "ready"
                        ),
                        () -> {
                            rerolls.incrementAndGet();
                            return fullPlan;
                        },
                        null
                );

        assertTrue(resolution.allowed());
        assertEquals(0, rerolls.get());
        assertEquals(1, resolution.committedCount());
        assertNotNull(resolution.missingPlan());
        assertEquals(List.of("child-1"), resolution.missingPlan().children().stream()
                .map(BreedingBirthPlan.PlannedChild::childKey)
                .toList());
        assertEquals(22, resolution.missingPlan().children().getFirst().spawnRole().roleIndex());
        assertEquals(
                TwBreedingConfig.Gender.Female,
                resolution.missingPlan().children().getFirst().spawnRole().gender()
        );
        assertEquals(snapshot, resolution.snapshot());
    }

    private static BreedingBirthPlan plan() {
        BreedingFertilityOffspringService.FertilityRoll fertility =
                new BreedingFertilityOffspringService.FertilityRoll(1.0, 2.0, 2.0, 2);
        return new BreedingBirthPlan(fertility, List.of(
                child("child-0", "baby-a", 21),
                child("child-1", "baby-b", 22)
        ));
    }

    private static BreedingBirthPlan.PlannedChild child(
            String key, String roleId, int roleIndex
    ) {
        return new BreedingBirthPlan.PlannedChild(
                key,
                new BreedingResolvedSpawnRole(
                        roleId, roleIndex, "adult", TwBreedingConfig.Gender.Female, null
                ),
                new BreedingOffspringProgressionService.OwnerSnapshot(
                        UUID.nameUUIDFromBytes(key.getBytes()), key
                ),
                "family"
        );
    }
}
