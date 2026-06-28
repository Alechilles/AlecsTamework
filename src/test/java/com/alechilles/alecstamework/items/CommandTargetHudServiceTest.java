package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudServiceTest {
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
                CommandTargetHudService.targetScanIntervalMsForTests() >= 75L,
                "Command target HUD should not raycast every world tick while a command item is held."
        );
        Assertions.assertTrue(
                CommandTargetHudService.targetScanIntervalMsForTests() <= 125L,
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
    void throttlesTargetScanForSameHeldCommandItemUntilIntervalElapses() {
        Assertions.assertFalse(CommandTargetHudService.shouldScanTargetForTests(
                "Tamework:CommandFlute",
                "Tamework:CommandFlute",
                1_000L,
                1_050L,
                100L
        ));
        Assertions.assertTrue(CommandTargetHudService.shouldScanTargetForTests(
                "Tamework:CommandFlute",
                "Tamework:CommandFlute",
                1_000L,
                1_100L,
                100L
        ));
    }

    @Test
    void targetScanRunsImmediatelyWhenHeldCommandItemChanges() {
        Assertions.assertTrue(CommandTargetHudService.shouldScanTargetForTests(
                "Tamework:CommandFlute",
                "Tamework:CommandWhistle",
                1_000L,
                1_010L,
                100L
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
    void throttlesBeforeBuildingExpensiveHudModelForSameTarget() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        int updatePlayer = source.indexOf("private void updatePlayer");
        int activationCheck = source.indexOf("activationTracker.shouldInspectPlayer(playerUuid, nowMs)", updatePlayer);
        int activeCommandResolve = source.indexOf("resolveActiveCommandItem(player)", updatePlayer);
        int scanCheck = source.indexOf("shouldScanTarget(previous, activeCommand.itemId(), nowMs)", updatePlayer);
        int targetResolve = source.indexOf("resolveTarget(player, playerRef, activeCommand, store)", updatePlayer);
        int refreshCheck = source.indexOf("shouldRefresh(previous, targetKey, nowMs)", updatePlayer);
        int modelBuild = source.indexOf("buildModel(player, candidate.npcRef(), candidate.npc(), store)", updatePlayer);

        Assertions.assertTrue(updatePlayer >= 0);
        Assertions.assertTrue(activationCheck > updatePlayer);
        Assertions.assertTrue(activeCommandResolve > activationCheck);
        Assertions.assertTrue(scanCheck > activeCommandResolve);
        Assertions.assertTrue(targetResolve > scanCheck);
        Assertions.assertTrue(refreshCheck > targetResolve);
        Assertions.assertTrue(modelBuild > refreshCheck);
    }

    @Test
    void commandTargetHudRegistersInventoryEventGateSystems() throws Exception {
        String tamework = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/Tamework.java"));
        String activeSlotSystem = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudActiveSlotSystem.java"
        ));
        String inventoryChangeSystem = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudInventoryChangeSystem.java"
        ));

        Assertions.assertTrue(tamework.contains("CommandTargetHudActivationTracker"));
        Assertions.assertTrue(tamework.contains("new CommandTargetHudActiveSlotSystem(commandTargetHudActivationTracker)"));
        Assertions.assertTrue(tamework.contains("new CommandTargetHudInventoryChangeSystem(commandTargetHudActivationTracker)"));
        Assertions.assertTrue(tamework.contains("new CommandTargetHudService(commandItemRegistry, commandTargetHudActivationTracker)"));
        Assertions.assertTrue(activeSlotSystem.contains("extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent>"));
        Assertions.assertTrue(inventoryChangeSystem.contains("extends EntityEventSystem<EntityStore, InventoryChangeEvent>"));
    }

    @Test
    void commandTargetHudOnlyUsesPlayerSweepAsFallbackDiscovery() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        int tick = source.indexOf("public void tick");
        int processCandidates = source.indexOf("processCandidatePlayers(store, nowMs)", tick);
        int fallbackCall = source.indexOf("seedCandidatesFromPlayerSweep(store)", tick);
        int fallbackMethod = source.indexOf("private void seedCandidatesFromPlayerSweep");
        int directSweep = source.indexOf("store.forEachChunk", tick);

        Assertions.assertTrue(tick >= 0);
        Assertions.assertTrue(processCandidates > tick);
        Assertions.assertTrue(fallbackCall > tick);
        Assertions.assertTrue(fallbackMethod > tick);
        Assertions.assertTrue(directSweep > fallbackMethod);
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
    void hidePathRemovesCustomHudFromManager() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        Assertions.assertTrue(source.contains("previous.hud().hideNow()"));
        Assertions.assertTrue(source.contains("removeCustomHud(player.getPlayerRef(), TameworkCommandTargetHud.HUD_KEY)"));
    }

    @Test
    void inactiveCandidateDropPathHidesCustomHudBeforeDroppingState() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        int clearMethod = source.indexOf("private void dropInactiveCandidate");
        int hideCall = source.indexOf("previous.hud().hideNow()", clearMethod);
        int removeState = source.indexOf("stateByPlayer.remove(playerUuid)", clearMethod);

        Assertions.assertTrue(clearMethod >= 0);
        Assertions.assertTrue(hideCall > clearMethod);
        Assertions.assertTrue(removeState > hideCall);
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
