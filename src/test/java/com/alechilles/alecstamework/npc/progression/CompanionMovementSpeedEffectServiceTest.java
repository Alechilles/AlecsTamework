package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompanionMovementSpeedEffectServiceTest {

    @Test
    void nativeMountedRefreshAcceptsTheRecoveredSourceRoleAndPrecomputedMultiplier() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/progression/CompanionMovementSpeedEffectService.java"));

        assertEquals(true, source.contains("applyResolvedMultiplier"));
        assertEquals(true, source.contains("@Nullable String sourceRoleId"));
        assertEquals(true, source.contains("resolveManagedEffectId(quantizedMultiplier)"));
    }

    @Test
    void neutralPlanRemovesEveryOwnedMovementSpeedEffect() {
        assertEquals(
                List.of("Tw_MovementSpeed_105", "Tw_Trait_MoveSpeed_110"),
                CompanionMovementSpeedEffectService.effectIdsToRemove(
                        List.of("Tw_MovementSpeed_105", "Tw_Trait_MoveSpeed_110", "Base_Swiftness"),
                        null
                )
        );
    }

    @Test
    void replacementPlanKeepsDesiredManagedEffectAndRemovesOlderManagedEffect() {
        assertEquals(
                List.of("Tw_MovementSpeed_105"),
                CompanionMovementSpeedEffectService.effectIdsToRemove(
                        List.of("Tw_MovementSpeed_105", "Tw_MovementSpeed_125", "ThirdParty_Haste"),
                        "Tw_MovementSpeed_125"
                )
        );
    }

    @Test
    void replacementPlanRemovesLegacyTraitSpeedEffects() {
        assertEquals(
                List.of("Tw_Trait_MoveSpeed_110"),
                CompanionMovementSpeedEffectService.effectIdsToRemove(
                        List.of("Tw_Trait_MoveSpeed_110", "Tw_MovementSpeed_125"),
                        "Tw_MovementSpeed_125"
                )
        );
    }

    @Test
    void replacementPlanPreservesUnrelatedAndMalformedEffectIds() {
        assertEquals(
                List.of(),
                CompanionMovementSpeedEffectService.effectIdsToRemove(
                        List.of("Base_Swiftness", "ThirdParty_MoveSpeed", "Tw_MovementSpeed_107", "Tw_Trait_MoveSpeed_131"),
                        "Tw_MovementSpeed_125"
                )
        );
    }

    @Test
    void missingDesiredAssetPlanDoesNotScheduleAnAddition() {
        assertEquals(
                null,
                CompanionMovementSpeedEffectService.effectIdToAdd("Tw_MovementSpeed_125", false)
        );
    }

    @Test
    void rejectedDesiredEffectAdditionKeepsTheRefreshIncompleteForRetry() {
        assertFalse(CompanionMovementSpeedEffectService.isApplicationSuccessful(
                "Tw_MovementSpeed_125", false, false));
        assertTrue(CompanionMovementSpeedEffectService.isApplicationSuccessful(null, false, false));
        assertTrue(CompanionMovementSpeedEffectService.isApplicationSuccessful(
                "Tw_MovementSpeed_125", true, false));
    }
}
