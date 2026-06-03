package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionHarvestBonusServiceTest {
    @Test
    void dropDuplicateModeCanProcDropDuplication() {
        assertTrue(CompanionHarvestBonusService.shouldDuplicateDrops("DropDuplicate", 1.25, () -> 0.10));
        assertFalse(CompanionHarvestBonusService.shouldDuplicateDrops("DropDuplicate", 1.25, () -> 0.30));
    }

    @Test
    void cooldownPreserveModeDoesNotDuplicateDrops() {
        assertFalse(CompanionHarvestBonusService.shouldDuplicateDrops("CooldownPreserve", 1.95, () -> 0.0));
    }

    @Test
    void cooldownPreserveModeCanProcCooldownSkip() {
        assertTrue(CompanionHarvestBonusService.shouldPreserveCooldown("CooldownPreserve", 1.25, () -> 0.10));
        assertFalse(CompanionHarvestBonusService.shouldPreserveCooldown("CooldownPreserve", 1.25, () -> 0.30));
    }

    @Test
    void unknownModeFallsBackToDropDuplication() {
        assertTrue(CompanionHarvestBonusService.shouldDuplicateDrops("SomethingElse", 1.25, () -> 0.10));
        assertFalse(CompanionHarvestBonusService.shouldPreserveCooldown("SomethingElse", 1.25, () -> 0.10));
    }
}
