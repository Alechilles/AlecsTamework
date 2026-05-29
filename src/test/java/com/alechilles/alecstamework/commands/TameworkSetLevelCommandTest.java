package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkSetLevelCommandTest {

    @Test
    void resultMessageShowsAppliedLevelAndClamp() {
        CompanionLevelingService.SetLevelResult result =
                new CompanionLevelingService.SetLevelResult(true, null, 2, 25, 25, 12345.4);

        String message = TameworkSetLevelCommand.buildResultMessage(
                "npc-1",
                99,
                result
        );

        assertEquals(
                "Set level for NPC npc-1: requested=99, previous=2, applied=25/25, totalXp=12345 (clamped).",
                message
        );
    }
}
