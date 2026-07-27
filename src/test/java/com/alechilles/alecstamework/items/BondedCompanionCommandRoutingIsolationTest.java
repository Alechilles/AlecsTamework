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
}
