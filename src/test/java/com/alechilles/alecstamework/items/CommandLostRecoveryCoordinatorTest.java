package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLostRecoveryCoordinatorTest {
    @Test
    void finalizedRaceReplaysUsingPreFinalizationGeneration() {
        assertEquals(1L, CommandLostRecoveryCoordinator.expectedFinalizationGeneration(
                operation(NpcRecoveryOperationRepository.RecoveryState.FINALIZED, 2L, false)));
        assertEquals(1L, CommandLostRecoveryCoordinator.expectedFinalizationGeneration(
                operation(NpcRecoveryOperationRepository.RecoveryState.PROJECTION_CREATED, 1L, true)));
    }

    private NpcRecoveryOperationRepository.RecoveryOperation operation(
            NpcRecoveryOperationRepository.RecoveryState state,
            long generation,
            boolean active) {
        UUID source = new UUID(0L, 1L);
        UUID target = new UUID(0L, 2L);
        return new NpcRecoveryOperationRepository.RecoveryOperation(
                "operation-a", "profile-a", source, target, target,
                state, active, generation, 1, 1L, 1L,
                state == NpcRecoveryOperationRepository.RecoveryState.FINALIZED ? 1L : 0L,
                null);
    }
}
