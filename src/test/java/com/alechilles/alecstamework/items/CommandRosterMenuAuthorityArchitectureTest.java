package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards command-menu actions against bypassing canonical owner/family roster authority. */
class CommandRosterMenuAuthorityArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "items");

    @Test
    void manualLinkCannotMutatePerToolMembershipInRosterMode() throws Exception {
        String handler = source("CommandItemFeatureHandler.java");
        String panel = source("CommandPanelActionService.java");

        int directToggle = handler.indexOf("linkMutationService.tryToggleLink(");
        int directRosterGuard = handler.lastIndexOf(
                "!config.usesOwnerCommandFamilyRoster()", directToggle);
        assertTrue(directRosterGuard >= 0 && directRosterGuard < directToggle);

        int applyLink = panel.indexOf("void applyLink(");
        int panelRosterGuard = panel.indexOf("if (config.usesOwnerCommandFamilyRoster())", applyLink);
        int panelMutation = panel.indexOf("toolInventoryService.mutateToolStack", applyLink);
        assertTrue(panelRosterGuard > applyLink && panelRosterGuard < panelMutation);
    }

    @Test
    void releaseAndCullRemoveRosterMembershipBeforeOwningWorldMutation() throws Exception {
        String handler = source("CommandItemFeatureHandler.java");

        assertTrue(handler.contains("npcUuid -> applyMenuRelease(player, toolId, config, npcUuid)"));
        assertTrue(handler.contains("npcUuid -> applyMenuCull(player, toolId, config, npcUuid)"));
        assertOrdered(handler, "private void applyMenuRelease(",
                "rosterActionAuthority.removeMember(", "owningWorld.execute(",
                "ownerReleaseService.release(");
        assertOrdered(handler, "private void applyMenuCull(",
                "rosterActionAuthority.removeMember(", "owningWorld.execute(",
                "ownerCullService.cull(");
    }

    private static void assertOrdered(String source, String method, String removal,
                                      String worldContinuation, String mutation) {
        int methodStart = source.indexOf(method);
        int removalIndex = source.indexOf(removal, methodStart);
        int worldIndex = source.indexOf(worldContinuation, removalIndex);
        int mutationIndex = source.indexOf(mutation, worldIndex);
        assertTrue(methodStart >= 0 && removalIndex > methodStart
                && worldIndex > removalIndex && mutationIndex > worldIndex);
    }

    private static String source(String name) throws Exception {
        return Files.readString(ITEMS.resolve(name), StandardCharsets.UTF_8);
    }
}
