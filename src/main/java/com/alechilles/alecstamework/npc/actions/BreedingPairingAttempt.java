package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import java.util.UUID;

/** Durable identity and replay evidence selected for one pairing reservation. */
record BreedingPairingAttempt(
        UUID jobId,
        BreedingPopulationReplayState replayState,
        boolean replay) {
}
