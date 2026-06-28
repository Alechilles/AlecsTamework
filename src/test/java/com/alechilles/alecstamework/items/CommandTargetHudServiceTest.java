package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void sweepThrottleIsTrackedPerWorldStore() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        Assertions.assertTrue(source.contains("new IdentityHashMap<>()"));
        Assertions.assertTrue(source.contains("storeTickStateByStore.computeIfAbsent(store"));
        Assertions.assertTrue(source.contains("StoreTickState tickState = tickState(store)"));
        Assertions.assertTrue(source.contains("nowMs < tickState.nextSweepAtMs"));
        int sharedStateMap = source.indexOf("storeTickStateByStore");
        int constructor = source.indexOf("public CommandTargetHudService", sharedStateMap);
        String fieldSection = source.substring(sharedStateMap, constructor);
        Assertions.assertFalse(fieldSection.contains("private long nextSweepAtMs;"));
        Assertions.assertFalse(fieldSection.contains("private long nextFallbackDiscoveryAtMs;"));
        Assertions.assertFalse(fieldSection.contains("private int nextCandidateOffset;"));
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
    void presentationPulseIsCheapAndFrequentEnoughToKeepHudVisible() {
        Assertions.assertTrue(
                CommandTargetHudService.presentationPulseIntervalMsForTests()
                        >= CommandTargetHudService.targetScanIntervalMsForTests(),
                "Presentation should not pulse more often than the target raycast cadence."
        );
        Assertions.assertTrue(
                CommandTargetHudService.presentationPulseIntervalMsForTests() <= 300L,
                "Presentation must stay frequent enough that custom HUD visibility does not appear as periodic flashes."
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
    void pulsesPresentationOnlyForVisibleExistingHudAfterInterval() {
        long pulseIntervalMs = CommandTargetHudService.presentationPulseIntervalMsForTests();
        Assertions.assertFalse(CommandTargetHudService.shouldPulsePresentationForTests(
                true,
                true,
                1_000L,
                1_000L + pulseIntervalMs - 1L,
                pulseIntervalMs
        ));
        Assertions.assertTrue(CommandTargetHudService.shouldPulsePresentationForTests(
                true,
                true,
                1_000L,
                1_000L + pulseIntervalMs,
                pulseIntervalMs
        ));
        Assertions.assertFalse(CommandTargetHudService.shouldPulsePresentationForTests(
                false,
                true,
                1_000L,
                1_000L + pulseIntervalMs,
                pulseIntervalMs
        ));
        Assertions.assertFalse(CommandTargetHudService.shouldPulsePresentationForTests(
                true,
                false,
                1_000L,
                1_000L + pulseIntervalMs,
                pulseIntervalMs
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
        int pulsePresentation = source.indexOf("pulsePresentation(playerUuid, previous, nowMs)", refreshCheck);
        int rememberScan = source.indexOf("rememberScan(playerUuid, previous, activeCommand.itemId(), nowMs, presentationMs)", refreshCheck);
        int modelBuild = source.indexOf("buildModel(player, candidate.npcRef(), candidate.npc(), store, nowMs)", updatePlayer);

        Assertions.assertTrue(updatePlayer >= 0);
        Assertions.assertTrue(activationCheck > updatePlayer);
        Assertions.assertTrue(activeCommandResolve > activationCheck);
        Assertions.assertTrue(scanCheck > activeCommandResolve);
        Assertions.assertTrue(targetResolve > scanCheck);
        Assertions.assertTrue(refreshCheck > targetResolve);
        Assertions.assertTrue(pulsePresentation > refreshCheck);
        Assertions.assertTrue(rememberScan > pulsePresentation);
        Assertions.assertTrue(modelBuild > refreshCheck);
    }

    @Test
    void throttledSameTargetPulsesPresentationWithoutHudRebuild() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        int updatePlayer = source.indexOf("private void updatePlayer");
        int refreshBlock = source.indexOf("if (!shouldRefresh(previous, targetKey, nowMs))", updatePlayer);
        int pulsePresentation = source.indexOf("pulsePresentation(playerUuid, previous, nowMs)", refreshBlock);
        int rememberScan = source.indexOf("rememberScan(playerUuid, previous, activeCommand.itemId(), nowMs, presentationMs)", refreshBlock);
        int returnStatement = source.indexOf("return;", rememberScan);
        int modelBuild = source.indexOf("buildModel(player, candidate.npcRef(), candidate.npc(), store, nowMs)", updatePlayer);
        String refreshBody = source.substring(refreshBlock, returnStatement);

        Assertions.assertTrue(refreshBlock > updatePlayer);
        Assertions.assertTrue(pulsePresentation > refreshBlock);
        Assertions.assertTrue(rememberScan > pulsePresentation);
        Assertions.assertTrue(returnStatement > rememberScan);
        Assertions.assertTrue(modelBuild > returnStatement);
        Assertions.assertTrue(refreshBody.contains("pulsePresentation(playerUuid, previous, nowMs)"));
        Assertions.assertFalse(refreshBody.contains("refresh("));
    }

    @Test
    void targetScanThrottleStillAllowsPresentationPulse() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        int updatePlayer = source.indexOf("private void updatePlayer");
        int cachedScanGate = source.indexOf("!shouldScanTarget(previous, cachedActiveItemId, nowMs)", updatePlayer);
        int cachedPulse = source.indexOf("pulsePresentation(playerUuid, previous, nowMs)", cachedScanGate);
        int cachedRemember = source.indexOf("rememberPresentation(playerUuid, previous, presentationMs)", cachedPulse);
        int cachedReturn = source.indexOf("return;", cachedRemember);
        int activeScanGate = source.indexOf("!shouldScanTarget(previous, activeCommand.itemId(), nowMs)", cachedReturn);
        int activePulse = source.indexOf("pulsePresentation(playerUuid, previous, nowMs)", activeScanGate);
        int activeRemember = source.indexOf("rememberPresentation(playerUuid, previous, presentationMs)", activePulse);
        int activeReturn = source.indexOf("return;", activeRemember);
        String cachedGateBody = source.substring(cachedScanGate, cachedReturn);
        String activeGateBody = source.substring(activeScanGate, activeReturn);

        Assertions.assertTrue(cachedScanGate > updatePlayer);
        Assertions.assertTrue(cachedPulse > cachedScanGate);
        Assertions.assertTrue(cachedRemember > cachedPulse);
        Assertions.assertFalse(cachedGateBody.contains("rememberScan("));
        Assertions.assertTrue(activeScanGate > cachedReturn);
        Assertions.assertTrue(activePulse > activeScanGate);
        Assertions.assertTrue(activeRemember > activePulse);
        Assertions.assertFalse(activeGateBody.contains("rememberScan("));
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
        int tickState = source.indexOf("StoreTickState tickState = tickState(store)", tick);
        int processCandidates = source.indexOf("processCandidatePlayers(store, tickState, nowMs)", tick);
        int fallbackCall = source.indexOf("seedCandidatesFromPlayerSweep(store)", tick);
        int fallbackMethod = source.indexOf("private void seedCandidatesFromPlayerSweep");
        int directSweep = source.indexOf("store.forEachChunk", tick);

        Assertions.assertTrue(tick >= 0);
        Assertions.assertTrue(tickState > tick);
        Assertions.assertTrue(processCandidates > tickState);
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

        int hideMethod = source.indexOf("private void hideHud");
        int nullManagerBranch = source.indexOf("if (player == null || player.getPlayerRef() == null || player.getHudManager() == null)", hideMethod);
        int fallbackHide = source.indexOf("previous.hud().hideNow()", nullManagerBranch);
        int managerRemove = source.indexOf("removeCustomHud(player.getPlayerRef(), TameworkCommandTargetHud.HUD_KEY)", fallbackHide);

        Assertions.assertTrue(hideMethod >= 0);
        Assertions.assertTrue(fallbackHide > nullManagerBranch);
        Assertions.assertTrue(managerRemove > fallbackHide);
    }

    @Test
    void firstHudCreationRegistersHudAndReliesOnManagerShow() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        int showMethod = source.indexOf("private void showHud");
        int createBranch = source.indexOf("if (hud == null)", showMethod);
        int addHud = source.indexOf("player.getHudManager().addCustomHud(playerRef, hud)", createBranch);
        int refreshBranch = source.indexOf("} else {", addHud);
        String createBody = source.substring(createBranch, refreshBranch);

        Assertions.assertTrue(showMethod >= 0);
        Assertions.assertTrue(addHud > createBranch);
        Assertions.assertTrue(refreshBranch > addHud);
        Assertions.assertFalse(createBody.contains("hud.present()"));
    }

    @Test
    void missingPlayerInCurrentStoreDoesNotDropSharedHudCandidate() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTargetHudService.java"
        ));

        int processMethod = source.indexOf("private void processCandidatePlayers");
        int candidateNull = source.indexOf("if (candidate == null)", processMethod);
        int missingStore = source.indexOf("debugMissingFromStore(playerUuid, nowMs)", candidateNull);
        int continueStatement = source.indexOf("continue;", missingStore);
        String missingBranch = source.substring(candidateNull, continueStatement);
        int missingMethod = source.indexOf("private void debugMissingFromStore");
        int nextMethod = source.indexOf("private void debug(", missingMethod);
        String missingMethodBody = source.substring(missingMethod, nextMethod);

        Assertions.assertTrue(processMethod >= 0);
        Assertions.assertTrue(missingStore > candidateNull);
        Assertions.assertTrue(continueStatement > missingStore);
        Assertions.assertFalse(missingBranch.contains("dropInactiveCandidate("));
        Assertions.assertFalse(missingMethodBody.contains("activationTracker.remove("));
        Assertions.assertFalse(missingMethodBody.contains("stateByPlayer.remove("));
        Assertions.assertFalse(missingMethodBody.contains("hideNow()"));
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
