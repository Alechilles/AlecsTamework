package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudServiceTest {
    private static final UUID PLAYER_A = UUID.fromString("64fb45b0-142b-4930-8668-c78437a26bb4");
    private static final UUID PLAYER_B = UUID.fromString("80c1e2f4-f0f5-4ef0-a6ac-a30ef8f03950");
    private static final UUID PLAYER_C = UUID.fromString("ff139abd-e93d-4088-8662-b81ad48d2d9f");
    private static final UUID PLAYER_D = UUID.fromString("a6feef26-0951-4e2b-b523-a380d43e1922");

    @Test
    void sweepIntervalAvoidsPerTickCandidateChecks() {
        Assertions.assertTrue(
                CommandTargetHudService.sweepIntervalMsForTests() >= 50L,
                "Command target HUD should not inspect candidates every world tick."
        );
        Assertions.assertTrue(
                CommandTargetHudService.sweepIntervalMsForTests() <= 100L,
                "Command target HUD should still react quickly when targeting changes."
        );
    }

    @Test
    void targetScanIntervalAvoidsPerTickRaycasts() {
        Assertions.assertTrue(
                CommandTargetHudService.targetScanIntervalMsForTests() >= 200L,
                "Command target HUD should not raycast every world tick while a command item is held."
        );
        Assertions.assertTrue(
                CommandTargetHudService.targetScanIntervalMsForTests() <= 250L,
                "Command target HUD should still hide or switch targets quickly enough to feel responsive."
        );
    }

    @Test
    void targetDistanceSupportsLongerInspectionRange() {
        Assertions.assertEquals(15.0f, CommandTargetHudService.targetDistanceForTests(), 0.001f);
    }

    @Test
    void sameTargetRefreshIntervalAvoidsFrequentHudRebuilds() {
        Assertions.assertEquals(
                5_000L,
                CommandTargetHudService.refreshIntervalMsForTests(),
                "Same-target HUD status refreshes can be slow because target scanning handles responsiveness."
        );
    }

    @Test
    void compactHudSnapshotsSkipLinkedPanelOnlyDetails() {
        CommandLoadedNpcStatusSnapshotService.SnapshotOptions options =
                CommandLoadedNpcStatusSnapshotService.SnapshotOptions.compactHud();

        Assertions.assertFalse(options.includeHappinessBreakdown());
        Assertions.assertFalse(options.includeProgressionModifierTooltip());
    }

    @Test
    void staticDisplayCacheExpiresAfterTtl() {
        Assertions.assertTrue(CommandTargetHudService.isStaticDisplayCacheValidForTests(
                1_000L,
                1_500L,
                30_000L
        ));
        Assertions.assertFalse(CommandTargetHudService.isStaticDisplayCacheValidForTests(
                1_000L,
                31_001L,
                30_000L
        ));
    }

    @Test
    void candidateSelectionCapsAndRotatesFromOffset() {
        Assertions.assertEquals(
                List.of(PLAYER_B, PLAYER_C),
                CommandTargetHudService.selectCandidatesForPassForTests(
                        List.of(PLAYER_A, PLAYER_B, PLAYER_C, PLAYER_D),
                        2,
                        1
                )
        );
    }

    @Test
    void candidateSelectionWrapsAroundCandidateList() {
        Assertions.assertEquals(
                List.of(PLAYER_D, PLAYER_A),
                CommandTargetHudService.selectCandidatesForPassForTests(
                        List.of(PLAYER_A, PLAYER_B, PLAYER_C, PLAYER_D),
                        2,
                        3
                )
        );
    }

    @Test
    void throttlesTargetScanForSameHeldCommandItemUntilIntervalElapses() {
        Assertions.assertFalse(CommandTargetHudService.shouldScanTargetForTests(
                "Tamework:CommandFlute",
                "Tamework:CommandFlute",
                1_000L,
                1_050L,
                200L
        ));
        Assertions.assertTrue(CommandTargetHudService.shouldScanTargetForTests(
                "Tamework:CommandFlute",
                "Tamework:CommandFlute",
                1_000L,
                1_200L,
                200L
        ));
    }

    @Test
    void targetScanRunsImmediatelyWhenHeldCommandItemChanges() {
        Assertions.assertTrue(CommandTargetHudService.shouldScanTargetForTests(
                "Tamework:CommandFlute",
                "Tamework:CommandWhistle",
                1_000L,
                1_010L,
                200L
        ));
    }

    @Test
    void refreshesWhenTargetChanges() {
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                "npc-b",
                1_000L,
                1_100L,
                CommandTargetHudService.refreshIntervalMsForTests()
        ));
    }

    @Test
    void refreshesWhenHeldCommandItemChangesForSameTarget() {
        UUID npcUuid = UUID.fromString("2ef48f27-68c3-47e8-9629-b9e1c9cf2ddf");
        String whistleTarget = CommandTargetHudService.buildTargetKeyForTests(npcUuid, "Tamework:CommandWhistle");
        String fluteTarget = CommandTargetHudService.buildTargetKeyForTests(npcUuid, "Tamework:CommandFlute");

        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                whistleTarget,
                fluteTarget,
                1_000L,
                1_100L,
                CommandTargetHudService.refreshIntervalMsForTests()
        ));
    }

    @Test
    void throttlesSameTargetUntilIntervalElapses() {
        long refreshIntervalMs = CommandTargetHudService.refreshIntervalMsForTests();
        Assertions.assertFalse(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                "npc-a",
                1_000L,
                1_000L + refreshIntervalMs - 1L,
                refreshIntervalMs
        ));
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                "npc-a",
                1_000L,
                1_000L + refreshIntervalMs,
                refreshIntervalMs
        ));
    }

    @Test
    void refreshesWhenHudNeedsClearing() {
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                null,
                1_000L,
                1_100L,
                CommandTargetHudService.refreshIntervalMsForTests()
        ));
        Assertions.assertFalse(CommandTargetHudService.shouldRefreshForTests(
                null,
                null,
                1_000L,
                1_100L,
                CommandTargetHudService.refreshIntervalMsForTests()
        ));
    }

    @Test
    void doesNotRepeatClearForAlreadyHiddenHud() {
        Assertions.assertFalse(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                false,
                null,
                1_000L,
                1_100L,
                CommandTargetHudService.refreshIntervalMsForTests()
        ));
    }

    @Test
    void refreshesWhenHiddenHudFindsNewTarget() {
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                null,
                false,
                "npc-a",
                1_000L,
                1_100L,
                CommandTargetHudService.refreshIntervalMsForTests()
        ));
    }

    @Test
    void untamedTameableTargetsDisplayEvenWhenCommandLinkRulesRejectThem() {
        Assertions.assertTrue(CommandTargetHudService.shouldShowForEligibility(false, false, true));
    }

    @Test
    void untamedNonTameableTargetsNeedCommandEligibility() {
        Assertions.assertFalse(CommandTargetHudService.shouldShowForEligibility(false, false, false));
        Assertions.assertTrue(CommandTargetHudService.shouldShowForEligibility(false, true, false));
    }

    @Test
    void tamedTargetsStillRequireCommandEligibility() {
        Assertions.assertFalse(CommandTargetHudService.shouldShowForEligibility(true, false, true));
        Assertions.assertTrue(CommandTargetHudService.shouldShowForEligibility(true, true, false));
    }

    @Test
    void parsesTranquilizerRequirementSeconds() {
        double seconds = CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "TameworkEffectActive",
                "{\"EffectId\":\"Tw_Status_Tranquilized\",\"MinRemainingSeconds\":90}"
        );

        Assertions.assertEquals(90.0, seconds, 0.001);
    }

    @Test
    void ignoresNonTranquilizerRequirementPayloads() {
        Assertions.assertEquals(0.0, CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "TameworkEffectActive",
                "{\"EffectId\":\"Other\",\"MinRemainingSeconds\":90}"
        ));
        Assertions.assertEquals(0.0, CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "OtherRequirement",
                "{\"EffectId\":\"Tw_Status_Tranquilized\",\"MinRemainingSeconds\":90}"
        ));
        Assertions.assertEquals(0.0, CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "TameworkEffectActive",
                "not-json"
        ));
    }

    @Test
    void attachmentDisplayFallsBackToCurrentModelSelectionsForUntamedTargets() {
        Map<String, String> modelAttachments = Map.of("Antlers", "Brown");

        Assertions.assertEquals(
                modelAttachments,
                CommandTargetHudService.resolveDisplayAttachmentIds(null, modelAttachments)
        );
    }

    @Test
    void attachmentDisplayPrefersPersistedSelectionsWhenPresent() {
        Map<String, String> persistedAttachments = Map.of("Antlers", "Dark");
        Map<String, String> modelAttachments = Map.of("Antlers", "Brown");

        Assertions.assertEquals(
                persistedAttachments,
                CommandTargetHudService.resolveDisplayAttachmentIds(persistedAttachments, modelAttachments)
        );
    }

    @Test
    void ownerDisplayUsesStoredOwnerNameOnlyWhenPresent() {
        Assertions.assertEquals(
                "Alec",
                CommandTargetHudService.resolveOwnerDisplayNameForTests(
                        new TameworkOwnerComponent(UUID.randomUUID(), "Alec")
                )
        );
        Assertions.assertNull(CommandTargetHudService.resolveOwnerDisplayNameForTests(
                new TameworkOwnerComponent(UUID.randomUUID(), " ")
        ));
        Assertions.assertNull(CommandTargetHudService.resolveOwnerDisplayNameForTests(
                new TameworkOwnerComponent(null, "Alec")
        ));
    }
}
