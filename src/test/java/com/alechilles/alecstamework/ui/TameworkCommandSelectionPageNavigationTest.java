package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkCommandSelectionPageNavigationTest {

    private static final Path SELECTION_PAGE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "ui",
            "TameworkCommandSelectionPage.java"
    );

    @Test
    void talentsNavigationUsesDeferredPageSwapInsteadOfClosingFirst() throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int branchStart = content.indexOf("commandId.startsWith(OPEN_TALENTS_COMMAND_PREFIX)");
        int branchEnd = content.indexOf(
                "if (!CommandSelectionOptionSource.contains(options, commandId))",
                branchStart
        );

        assertTrue(branchStart >= 0, "Talent navigation branch should exist.");
        assertTrue(branchEnd > branchStart, "Talent navigation branch should be bounded by the fallback branch.");

        String branch = content.substring(branchStart, branchEnd);
        assertTrue(
                branch.contains("beginPageNavigation()"),
                "Talent navigation should mark the linked panel inactive before opening another page."
        );
        assertTrue(
                branch.contains("navigateAfterUiDrain"),
                "Talent navigation should defer the page swap so stale linked-panel updates drain first."
        );
        assertFalse(
                branch.contains("closePage()"),
                "Closing the linked panel before opening talents can close the new page or leave stale linked-panel UI commands."
        );
    }

    @Test
    void pageNavigationStopsLinkedPanelRefreshCallbacks() throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int helperStart = content.indexOf("private boolean beginPageNavigation()");
        int helperEnd = content.indexOf("private void navigateAfterUiDrain", helperStart);

        assertTrue(helperStart >= 0, "Page navigation helper should exist.");
        assertTrue(helperEnd > helperStart, "Page navigation helper should be bounded by the navigation dispatcher.");

        String helper = content.substring(helperStart, helperEnd);
        assertTrue(
                helper.contains("dismissed = true"),
                "Opening a replacement page should stop this page's delayed refresh loop."
        );
        assertTrue(
                helper.contains("clearLinkedPanelOwner()"),
                "Opening a replacement page should invalidate stale linked-panel refresh ownership."
        );
        assertTrue(
                helper.contains("cancelPendingFilterTextApply()"),
                "Opening a replacement page should cancel delayed filter writes for the old page."
        );
        assertFalse(
                helper.contains("close()"),
                "Replacement-page navigation should not close the page manager while opening the next page."
        );
    }

    @Test
    void replacementPageNavigationWaitsForQueuedUiCommandsToDrain() throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int helperStart = content.indexOf("private void navigateAfterUiDrain");
        int helperEnd = content.indexOf("private void bindLinkedNpcCard", helperStart);

        assertTrue(helperStart >= 0, "Deferred page navigation helper should exist.");
        assertTrue(helperEnd > helperStart, "Deferred page navigation helper should be bounded by the dispatcher.");

        String helper = content.substring(helperStart, helperEnd);
        assertTrue(
                content.contains("PAGE_NAVIGATION_DRAIN_DELAY_MS"),
                "Replacement-page navigation should use an explicit UI drain delay."
        );
        assertTrue(
                helper.contains("CompletableFuture.delayedExecutor(PAGE_NAVIGATION_DRAIN_DELAY_MS"),
                "Replacement-page navigation should wait briefly so already-sent linked-panel commands apply before the new page opens."
        );
        assertTrue(
                content.contains("PAGE_NAVIGATION_DRAIN_DELAY_MS = 100L"),
                "Replacement-page navigation should use only a short drain delay now that stale refresh owners are blocked."
        );
        assertTrue(
                helper.contains("CommandPageWorldDispatcher.dispatch("),
                "Replacement-page navigation should still run the replacement-page open on the world thread."
        );
    }

    @Test
    void linkedPanelRefreshUpdatesRequireCurrentPageOwnership() throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int updateStart = content.indexOf("private void sendCardRefreshUpdate()");
        int updateEnd = content.indexOf("private boolean isPendingUnlink", updateStart);
        int dismissStart = content.indexOf("public void onDismiss(");
        int dismissEnd = content.indexOf("private void buildLinkedNpcPanel", dismissStart);

        assertTrue(updateStart >= 0, "Linked-panel refresh sender should exist.");
        assertTrue(updateEnd > updateStart, "Linked-panel refresh sender should be bounded by the next helper.");
        assertTrue(dismissStart >= 0, "Dismiss lifecycle hook should exist.");
        assertTrue(dismissEnd > dismissStart, "Dismiss lifecycle hook should be bounded by buildCommandButtons.");

        String update = content.substring(updateStart, updateEnd);
        String dismiss = content.substring(dismissStart, dismissEnd);
        assertTrue(
                content.contains("ACTIVE_LINKED_PANEL_GENERATIONS"),
                "Linked-panel pages should track the active page generation per player."
        );
        assertTrue(
                content.contains("markLinkedPanelOwner()"),
                "Constructed linked-panel pages should claim ownership before scheduling refreshes."
        );
        assertTrue(
                update.contains("!isCurrentLinkedPanelOwner()"),
                "Delayed linked-panel refreshes should not send commands from stale page instances."
        );
        assertTrue(
                dismiss.contains("clearLinkedPanelOwner()"),
                "Dismissing a linked panel should clear ownership for that page generation."
        );
    }

    @Test
    void timerPresentationChangesDoNotClearAndRecreateTheLinkedPanelList()
            throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int updateStart = content.indexOf("private void sendCardRefreshUpdate()");
        int updateEnd = content.indexOf("private boolean isPendingUnlink", updateStart);

        assertTrue(updateStart >= 0, "Linked-panel refresh sender should exist.");
        assertTrue(updateEnd > updateStart, "Linked-panel refresh sender should be bounded by the next helper.");

        String update = content.substring(updateStart, updateEnd);
        assertFalse(update.contains("renderedFeatureRevision != featureController.revision()"),
                "countdown updates must not clear and recreate every card");
        assertTrue(update.contains("refreshDynamicState"),
                "countdown updates should patch the existing bonded card in place");
    }

    @Test
    void lightweightRefreshesRebindBondedCardInput() throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int updateStart = content.indexOf("private void sendCardRefreshUpdate()");
        int updateEnd = content.indexOf("private void closePage()", updateStart);

        assertTrue(updateStart >= 0, "Linked-panel refresh sender should exist.");
        assertTrue(updateEnd > updateStart, "Refresh sender should be bounded by page navigation.");

        String update = content.substring(updateStart, updateEnd);
        assertTrue(update.contains("bindBondedCardEvents(eventBuilder"),
                "Refresh packets must retain the bonded card's talent click handler.");
        assertTrue(content.contains("private void bindBondedCardEvents"),
                "Bonded input rebinding should be isolated from visual card rendering.");
    }

    @Test
    void talentNavigationHasOptInBoundaryTracingForLiveDiagnosis()
            throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int eventStart = content.indexOf("public void handleDataEvent(");
        int helperStart = content.indexOf("private static void logTalentNavigation");

        assertTrue(eventStart >= 0, "The page event handler should exist.");
        assertTrue(helperStart > eventStart,
                "Talent navigation diagnostics should be isolated in a helper.");

        String eventHandler = content.substring(eventStart, helperStart);
        assertTrue(eventHandler.contains("event received command="),
                "Live traces must establish whether the click reached the server page.");
        assertTrue(eventHandler.contains("navigation queued npc="),
                "Live traces must establish whether the decoded click reaches page navigation.");
        assertTrue(eventHandler.contains("navigation dispatch npc="),
                "Live traces must establish whether the deferred page open actually runs.");
        assertTrue(content.contains("Bonded talent navigation:"),
                "Temporary tracing must identify the navigation boundary in the server log.");
    }

    @Test
    void refreshTickPatchesUnchangedPanelChromeOnlyWhenItsValueChanges()
            throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);
        int updateStart = content.indexOf("private void sendCardRefreshUpdate()");
        int updateEnd = content.indexOf("CommandSelectionPageEventBinder.bindOptionEvents", updateStart);

        assertTrue(updateStart >= 0, "Linked-panel refresh sender should exist.");
        assertTrue(updateEnd > updateStart, "Refresh sender should include panel chrome updates.");

        String update = content.substring(updateStart, updateEnd);
        assertTrue(update.contains("refreshValues.set(commandBuilder, \"#TameworkLinkedPanelTitle.Text\""),
                "unchanged panel title must not be resent each second");
        assertTrue(update.contains("refreshValues.set(commandBuilder, \"#TameworkLinkedPanelModeDropdown.Value\""),
                "unchanged panel controls must not be rebuilt each second");
    }

    @Test
    void delayedRefreshGuardsWorldExecute() throws IOException {
        String content = Files.readString(SELECTION_PAGE, StandardCharsets.UTF_8);

        assertTrue(content.contains("CommandPageWorldDispatcher.dispatch("),
                "delayed refreshes should return to the world-thread dispatcher");
        assertTrue(content.contains("activeRef != null && activeRef.isValid()"),
                "delayed navigation should verify the player reference before applying UI work");
    }
}
