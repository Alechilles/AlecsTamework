package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimalStoredProgressionTest {
    @TempDir Path directory;

    @Test
    void capturedSnapshotStopsActiveTimeWithoutChangingTheLiveAnimal() {
        AnimalProgressionClock clock = AnimalProgressionClock.get();
        clock.clearForTests();
        try {
            clock.start(directory);
            UUID owner = UUID.randomUUID();
            clock.onOwnerConnected(owner);
            TameworkLifeStageComponent live = new TameworkLifeStageComponent();
            live.setProgressionInitialized(true);
            live.setProgressionOwnerId(owner.toString());
            live.setProgressionClockMs(clock.current(owner));
            live.setActiveProgressMs(120_000L);
            live.setStage("Baby");
            live.setGrowthScalingEnabled(true);
            live.setBornAtMs(1_000L);
            live.setAdolescentAtMs(1_500L);
            live.setAdultAtMs(2_000L);
            live.setLastProgressionWorldMs(1_000L);
            TameworkLifeStageComponent captured = live.clone();
            AnimalProgressionService.pauseStored(captured);
            captured = TameworkLifeStageComponent.CODEC.decode(TameworkLifeStageComponent.CODEC.encode(captured));
            CompanionRuntimeClock.advanceByDeltaSeconds(3_600f);

            assertEquals(120_000L, AnimalProgressionService.activeTimeMs(captured));
            assertEquals(3_720_000L, AnimalProgressionService.activeTimeMs(live));
            assertEquals("Baby", CompanionLifeStageService.resolveStageId(
                    captured, AnimalProgressionService.lifeTime(captured, null)));
        } finally {
            clock.clearForTests();
        }
    }
}
