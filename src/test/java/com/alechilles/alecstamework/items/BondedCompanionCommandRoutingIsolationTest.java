package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Prevents bonded Horn commands from leaking into generic link persistence. */
class BondedCompanionCommandRoutingIsolationTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");

    @Test
    void orchestratorSkipsEveryGenericLinkedRecordMutationForBondedTools()
            throws IOException {
        String source = Files.readString(ITEMS.resolve(
                "CommandItemUseOrchestrator.java"));

        assertTrue(source.contains(
                "usesGenericLinkedRecords(config)\n"
                        + "                ? linkedRecordReconciler.reconcile("));
        assertTrue(source.contains(
                "if (!usesGenericLinkedRecords(use.config)\n"
                        + "                || targetRef == null"));
        assertTrue(source.contains(
                "if (!usesGenericLinkedRecords(context.config)) {\n"
                        + "            return;\n"
                        + "        }\n"
                        + "        ItemStack refreshed = linkMutationService"
                        + ".refreshLinkedNpcPositions("));
    }

    @Test
    void commandStepsRetainLiveHomeButNeverPersistBondedItemLinks()
            throws IOException {
        String source = Files.readString(ITEMS.resolve(
                "CommandStepExecutionService.java"));

        assertTrue(source.contains("links.setHomePosition(home);"));
        assertTrue(source.contains(
                "if (!context.config.usesBondedCompanionRoster()\n"
                        + "                && context.workingItem != null"));
    }

    @Test
    void bondedMovesNeverEnterGenericDeferredRelocation() throws IOException {
        String dispatch = Files.readString(ITEMS.resolve(
                "CommandRelocationDispatchService.java"));
        String steps = Files.readString(ITEMS.resolve(
                "CommandStepExecutionService.java"));

        assertTrue(dispatch.contains(
                "if (context.config.usesBondedCompanionRoster()) {\n"
                        + "            return;\n"
                        + "        }\n"
                        + "        if (!resolutionService.isRecallCommand"));
        assertTrue(steps.contains(
                "if (!context.config.usesBondedCompanionRoster()\n"
                        + "                    && CommandTravelSettings"
                        + ".isRecallTeleportingEnabled()"));
    }

    @Test
    void recipientServiceBypassesGenericScanAndUnloadedRelocation()
            throws IOException {
        String source = Files.readString(ITEMS.resolve(
                "CommandRecipientService.java"));
        int bonded = source.indexOf(
                "return bondedRecipients.queryRecipients(context);");
        int genericScan = source.indexOf("context.store.forEachChunk(Query.any()");
        int unloadedGuard = source.indexOf(
                "if (context.config.usesBondedCompanionRoster()) {\n"
                        + "            return List.of();");

        assertTrue(bonded >= 0 && genericScan > bonded);
        assertTrue(unloadedGuard > genericScan);
    }

    @Test
    void everyGenericLinkCreationEntryPointRejectsBondedStorage()
            throws IOException {
        String mutations = Files.readString(ITEMS.resolve(
                "CommandLinkMutationService.java"));
        String autoLink = Files.readString(ITEMS.resolve(
                "CommandAutoLinkService.java"));
        String spawn = Files.readString(ITEMS.resolve(
                "NpcSpawnCommandService.java"));

        assertTrue(mutations.contains(
                "if (config == null || config.usesBondedCompanionRoster()) {\n"
                        + "            return LinkToggleResult.notToggled();"));
        assertTrue(autoLink.contains(
                "if (config == null || config.usesBondedCompanionRoster()) {\n"
                        + "                continue;"));
        assertTrue(spawn.contains(
                "config.usesBondedCompanionRoster()\n"
                        + "                || !config.isEnabled()"));
    }

    @Test
    void directLinkMutationFailsClosedBeforeAnyEcsAccess() {
        TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "RosterStorage":"BondedCompanions",
                          "BondedRosterId":"hydragon:bonded"
                        }
                        """), new ExtraInfo());
        CommandLinkMutationService service = new CommandLinkMutationService(
                null, null, null);

        LinkToggleResult result = service.tryToggleLink(
                null, null, null, "tool", config, null);

        assertFalse(result.toggled);
        assertFalse(result.linked);
        assertFalse(result.pending);
    }

    /**
     * Keeps every non-UI generic action boundary closed even when a stale or
     * forged callback bypasses the bonded page's event filter.
     */
    @Test
    void genericActionBackendsRejectBondedStorageBeforeLegacyStateAccess()
            throws IOException {
        String handler = Files.readString(ITEMS.resolve(
                "CommandItemFeatureHandler.java"));
        String travel = Files.readString(ITEMS.resolve(
                "CommandWorldChangeTravelCoordinator.java"));
        assertGuardBefore(travel,
                "private void queue(",
                "String toolId = stack.getFromMetadataOrNull(");
        assertCallbackAuthorityBefore(handler,
                "private void openGroupManagerFromSelection(",
                "groupManagerPageService.openGroupManagerPage(");
        assertCallbackAuthorityBefore(handler, "private void applyMenuUnlink(",
                "Inventory inventory = player.getInventory();");
        assertCallbackAuthorityBefore(handler, "private void applyMenuRelease(",
                "ownerReleaseService.release(");
        assertCallbackAuthorityBefore(handler, "private void applyMenuCull(",
                "ownerCullService.cull(");
        assertCallbackAuthorityBefore(handler, "private void applyMenuRespawn(",
                "freeRestorationActions.request(");
        assertCallbackAuthorityBefore(handler, "private void applyMenuSetHome(",
                "Inventory inventory = player.getInventory();");
        assertCallbackAuthorityBefore(handler, "private void applyMenuLocate(",
                "locateService.locate(");
        assertCallbackAuthorityBefore(handler, "private void applyMenuMoveCommand(",
                "menuMoveService.applyMenuMoveCommand(");

        String panel = Files.readString(ITEMS.resolve(
                "CommandPanelActionService.java"));
        assertGuardBefore(panel, "void applyLink(", "World world = player.getWorld();");
        assertGuardBefore(panel, "void applyToggleActive(",
                "toolInventoryService.mutateToolStack(");
        assertGuardBefore(panel, "void applyToggleBreeding(",
                "toolInventoryService.mutateToolStack(");
        assertGuardBefore(panel, "void applySetAutoLinkEnabled(",
                "toolInventoryService.mutateToolStack(");
        assertGuardBefore(panel, "void applySetLinkedNpcGroup(",
                "groupActionService.applySetLinkedNpcGroup(");

        String groups = Files.readString(ITEMS.resolve(
                "CommandGroupAssignPageService.java"));
        assertGuardBefore(groups, "void applyGroupActivation(",
                "toolInventoryService.mutateToolStack(");
        assertGuardBefore(groups, "void applyGroupAssignment(",
                "if (groupId != null && !isNpcLinkedToTool(");
    }

    @Test
    void selectionPageReplacesGenericCallbacksForBondedStorage()
            throws IOException {
        String source = Files.readString(ITEMS.resolve(
                "CommandSelectionPageService.java"));

        assertTrue(source.contains(
                "CommandRosterStorageBoundary.allowsGenericRosterActions(config)"));
        assertTrue(source.contains("BooleanSupplier genericCallbackAuthority"));
        assertTrue(source.contains("guardedUuid(guardedGenericCallbacks,"));
        assertTrue(source.contains("guardedBoolean(guardedGenericCallbacks,"));
        assertTrue(source.contains("guardedPair(guardedGenericCallbacks,"));
        assertTrue(source.contains("guardedAction(guardedGenericCallbacks,"));
        assertTrue(source.contains("panelPreferenceAuthority"));
    }

    @Test
    void authorityPolicyIsWiredAtEveryGenericConsumerBoundary()
            throws IOException {
        String panel = Files.readString(ITEMS.resolve(
                "CommandPanelEntrySourceService.java"));
        String release = Files.readString(ITEMS.resolve(
                "CommandOwnerReleaseService.java"));
        String cull = Files.readString(ITEMS.resolve(
                "CommandOwnerCullService.java"));
        String handler = Files.readString(ITEMS.resolve(
                "CommandItemFeatureHandler.java"));
        String groups = Files.readString(ITEMS.resolve(
                "CommandGroupManagerPageService.java"));

        assertTrue(panel.contains("allowsNearbyPresentation("));
        assertBefore(release, "allowsGenericTargetMutation(", "canRelease(");
        assertBefore(release, "allowsGenericTargetMutation(", "clearOwner(");
        assertBefore(cull, "allowsGenericTargetMutation(", "canCull(");
        assertBefore(cull, "allowsGenericTargetMutation(",
                "clearNpcCommandLinks(target);");
        assertBefore(cull, "allowsGenericCullRepair(",
                "removeLinkedNpcRecord(stack, npcUuid)");
        assertTrue(handler.contains("findUniqueToolStack("));
        assertTrue(groups.contains("runIfAllowed(authority,"));
        assertTrue(groups.contains("authority.getAsBoolean()"));
    }

    private void assertGuardBefore(String source, String method,
                                   String firstLegacyAccess) {
        int methodStart = source.indexOf(method);
        int guard = source.indexOf(
                "CommandRosterStorageBoundary.allowsGenericRosterActions(config)",
                methodStart);
        int legacyAccess = source.indexOf(firstLegacyAccess, methodStart);

        assertTrue(methodStart >= 0, "Missing method: " + method);
        assertTrue(guard > methodStart && legacyAccess > guard,
                "Bonded guard must precede legacy access in " + method);
    }

    private void assertCallbackAuthorityBefore(String source, String method,
                                               String callback) {
        int methodStart = source.indexOf(method);
        int guard = source.indexOf(
                "callbackAuthority.allowsGeneric(", methodStart);
        int callbackIndex = source.indexOf(callback, methodStart);

        assertTrue(methodStart >= 0, "Missing method: " + method);
        assertTrue(guard > methodStart && callbackIndex > guard,
                "Callback authority must precede generic manager opening in "
                        + method);
    }

    private void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0 && secondIndex > firstIndex,
                first + " must precede " + second);
    }
}
