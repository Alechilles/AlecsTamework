package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan;
import com.alechilles.alecstamework.npc.breeding.BreedingFertilitySnapshot;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regression: visually identical litter members retain distinct durable child identities. */
class BreedingJobPlanSnapshotMapperTest {
    @Test
    void identicalChildrenUseStableKeysAndReplayOmitsOnlyTheCommittedUnit() {
        PlannedChild identical = new PlannedChild(
                "baby-cow", "cow", "Female", "cattle:line-a", "cattle"
        );
        BreedingBirthPlan plan = new BreedingBirthPlan(
                new BreedingFertilitySnapshot(1.0, 1.0, 2.0, 0.5, 2),
                List.of(identical, identical)
        );
        BreedingJobPlanSnapshotMapper mapper = new BreedingJobPlanSnapshotMapper();
        BreedingBirthPlanSnapshot snapshot = mapper.snapshot(
                plan,
                null,
                new BreedingOffspringProgressionService.OwnerSnapshot(
                        new UUID(0L, 10L), "Owner"
                ),
                BreedingOffspringProgressionService.OwnerSnapshot.empty()
        );
        BreedingPopulationReplayState replay = new BreedingPopulationReplayState(
                true,
                "breeding:00000000-0000-0000-0000-000000000001",
                snapshot,
                Set.of("child-0001"),
                Set.of("child-0000"),
                "breeding-replay-ready"
        );

        assertEquals(List.of("child-0000", "child-0001"), snapshot.children().stream()
                .map(BreedingBirthPlanSnapshot.PlannedChild::childKey)
                .toList());
        assertEquals(List.of(identical), mapper.outstandingChildren(plan, replay));
        assertEquals(
                List.of("child-0001"),
                mapper.outstandingSnapshots(snapshot, replay).stream()
                        .map(BreedingBirthPlanSnapshot.PlannedChild::childKey)
                        .toList()
        );
        assertEquals(new UUID(0L, 10L), snapshot.children().get(1).ownerId());
    }

    @Test
    void capacityClampedChildrenWithoutJournalRowsAreNeverReplayed() {
        PlannedChild identical = new PlannedChild(
                "baby-cow", "cow", "Female", "cattle", "cattle"
        );
        BreedingBirthPlan plan = new BreedingBirthPlan(
                new BreedingFertilitySnapshot(2.0, 2.0, 4.0, 0.5, 4),
                List.of(identical, identical, identical, identical)
        );
        BreedingJobPlanSnapshotMapper mapper = new BreedingJobPlanSnapshotMapper();
        BreedingBirthPlanSnapshot snapshot = mapper.snapshot(
                plan,
                null,
                BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                BreedingOffspringProgressionService.OwnerSnapshot.empty()
        );
        BreedingPopulationReplayState completedClamp = new BreedingPopulationReplayState(
                true,
                "breeding:00000000-0000-0000-0000-000000000002",
                snapshot,
                Set.of(),
                Set.of("child-0000"),
                "breeding-replay-complete"
        );

        assertEquals(List.of(), mapper.outstandingChildren(plan, completedClamp));
        assertEquals(List.of(), mapper.outstandingSnapshots(snapshot, completedClamp));
    }

    @Test
    void durablePlanRestoresWithoutRerollingRoleLifecycleOrFertility() {
        BreedingBirthPlan plan = new BreedingBirthPlan(
                new BreedingFertilitySnapshot(0.75, 1.25, 1.0, 0.75, 1),
                List.of(new PlannedChild(
                        "baby-goat", "goat", "Male", "goats:mountain", "goats"
                ))
        );
        BreedingJobPlanSnapshotMapper mapper = new BreedingJobPlanSnapshotMapper();
        BreedingBirthPlanSnapshot snapshot = mapper.snapshot(
                plan,
                null,
                BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                BreedingOffspringProgressionService.OwnerSnapshot.empty()
        );

        BreedingBirthPlan restored = mapper.restore(snapshot);

        assertNotNull(restored);
        assertEquals(plan.children(), restored.children());
        assertEquals(plan.fertilitySnapshot().expectedOffspring(),
                restored.fertilitySnapshot().expectedOffspring());
        assertEquals(plan.rolledChildCount(), restored.rolledChildCount());
    }
}
