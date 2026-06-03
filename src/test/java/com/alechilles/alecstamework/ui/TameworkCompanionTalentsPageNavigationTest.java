package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkCompanionTalentsPageNavigationTest {

    private static final Path TALENTS_PAGE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "ui",
            "TameworkCompanionTalentsPage.java"
    );

    @Test
    void backActionReopensLinkedPanelAfterCurrentEventCallback() throws IOException {
        String content = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        int branchStart = content.indexOf("ACTION_BACK.equalsIgnoreCase(data.action)");
        int branchEnd = content.indexOf("ACTION_RESET.equalsIgnoreCase(data.action)", branchStart);

        assertTrue(branchStart >= 0, "Talent back branch should exist.");
        assertTrue(branchEnd > branchStart, "Talent back branch should be bounded by the next action branch.");

        String branch = content.substring(branchStart, branchEnd);
        assertTrue(
                branch.contains("navigationPending = true"),
                "Back navigation should block duplicate talent-page events while the linked panel is reopening."
        );
        assertTrue(
                branch.contains("navigateBackOnWorldThread()"),
                "Back navigation should defer the linked-panel reopen out of the current UI callback."
        );
        assertFalse(
                branch.contains("close()"),
                "Closing the talents page before reopening the linked panel can leave the replacement page unbound."
        );
        assertFalse(
                branch.contains("backCallback.run()"),
                "The linked panel should not reopen directly inside the talents page event callback."
        );
    }

    @Test
    void backNavigationRunsThroughWorldThread() throws IOException {
        String content = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        int helperStart = content.indexOf("private void navigateBackOnWorldThread()");
        int helperEnd = content.indexOf("private void bindPage", helperStart);

        assertTrue(helperStart >= 0, "Talent back navigation helper should exist.");
        assertTrue(helperEnd > helperStart, "Talent back navigation helper should be bounded by bindPage.");

        String helper = content.substring(helperStart, helperEnd);
        assertTrue(
                helper.contains("world.execute"),
                "Talent back navigation should reopen the linked panel through the world thread."
        );
        assertTrue(
                helper.contains("playerRef.getReference()"),
                "Talent back navigation should resolve a live player reference before reopening."
        );
        assertTrue(
                helper.contains("navigationPending = false"),
                "Talent back navigation should release the duplicate-event guard after the callback finishes."
        );
    }

    @Test
    void closedTalentPageDoesNotSendRefreshUpdates() throws IOException {
        String content = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        int helperStart = content.indexOf("private void sendRefreshUpdate()");
        int helperEnd = content.indexOf("private void navigateBackOnWorldThread()", helperStart);

        assertTrue(helperStart >= 0, "Talent page refresh helper should exist.");
        assertTrue(helperEnd > helperStart, "Talent page refresh helper should be bounded by navigation helper.");

        String helper = content.substring(helperStart, helperEnd);
        assertTrue(
                helper.contains("handled || navigationPending"),
                "Talent page refreshes should stop once the page is closing or navigating away."
        );
    }

    @Test
    void resetActionRefreshesTalentPageWithoutNavigatingAway() throws IOException {
        String content = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        int branchStart = content.indexOf("ACTION_RESET.equalsIgnoreCase(data.action)");
        int branchEnd = content.indexOf("data.action.startsWith(ACTION_SELECT_PREFIX)", branchStart);
        int bindStart = content.indexOf("commandBuilder.set(\"#TameworkCompanionTalentsResetButton.Visible\"");
        int bindEnd = content.indexOf("bindBranchSlots(commandBuilder", bindStart);

        assertTrue(branchStart >= 0, "Talent reset branch should exist.");
        assertTrue(branchEnd > branchStart, "Talent reset branch should run before node selection actions.");
        assertTrue(bindStart >= 0, "Talent reset button visibility should be bound.");
        assertTrue(bindEnd > bindStart, "Talent reset button binding should be bounded by tree binding.");

        String branch = content.substring(branchStart, branchEnd);
        String binding = content.substring(bindStart, bindEnd);
        assertTrue(
                branch.contains("resetCallback.get()"),
                "Reset action should run the reset callback."
        );
        assertTrue(
                branch.contains("sendRefreshUpdate()"),
                "Reset action should refresh the current talents page after refunding points."
        );
        assertFalse(
                branch.contains("navigateBackOnWorldThread()"),
                "Reset action should not navigate back to the linked panel."
        );
        assertTrue(
                binding.contains("data.canReset()"),
                "Reset button should only be visible and bound when the current companion has spent talents."
        );
        assertTrue(
                binding.contains("EventData.of(KEY_ACTION, ACTION_RESET)"),
                "Reset button should emit the reset action payload."
        );
    }

    @Test
    void pageDataCarriesTreeNodeBranchTierAndState() throws IOException {
        String content = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);

        assertTrue(content.contains("TreeNodeEntry"), "Talent page should expose tree node view data.");
        assertTrue(content.contains("branchName"), "Tree nodes should carry the branch column label.");
        assertTrue(content.contains("tier"), "Tree nodes should carry the tier row.");
        assertTrue(content.contains("state"), "Tree nodes should carry display state.");
        assertTrue(content.contains("pointCost"), "Tree nodes should carry their point cost.");
        assertTrue(content.contains("requiredTalentIds"), "Tree nodes should carry prerequisite links.");
        assertTrue(content.contains("requiredTalentNames"), "Tree nodes should carry prerequisite display names.");
        assertTrue(content.contains("effectSummary"), "Tree nodes should carry passive effect summaries.");
        assertTrue(content.contains("Purchased"), "Tree node states should include purchased talents.");
        assertTrue(content.contains("Locked"), "Tree node states should include locked talents.");
        assertTrue(content.contains("Available"), "Tree node states should include purchasable talents.");
    }

    @Test
    void talentDetailsFormatRequirementsAsSeparateLines() throws IOException {
        String content = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        int start = content.indexOf("private String resolveRequirementText");
        int end = content.indexOf("private void bindNodeState", start);
        String method = content.substring(start, end);

        assertTrue(method.contains("\"Level \" + entry.minLevel()"), "Requirements should show level without extra prose.");
        assertTrue(method.contains("entry.requiredTalentNames()"), "Requirements should use prerequisite display names.");
        assertTrue(method.contains("String.join(\"\\n\", lines)"), "Requirements should render one line per requirement.");
    }

    @Test
    void talentPageUsesScrollableTreeInsteadOfPagination() throws IOException {
        String page = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        String ui = Files.readString(
                Path.of("src", "main", "resources", "Common", "UI", "Custom", "TameworkCompanionTalentsPage.ui"),
                StandardCharsets.UTF_8
        );

        assertTrue(ui.contains("Group #TalentTreeViewport"), "Talent page should expose a tree viewport.");
        assertTrue(ui.contains("LayoutMode: TopScrolling"), "Oversized talent trees should scroll instead of page.");
        assertTrue(ui.contains("KeepScrollPosition: true"), "Talent tree scroll position should survive refreshes.");
        assertTrue(page.contains("commandBuilder.clear(\"#TalentNodeLayer\")"), "Tree refresh should rebuild dynamic nodes.");
        assertTrue(page.contains("ACTION_SELECT_PREFIX"), "Node clicks should select a talent.");
        assertTrue(page.contains("ACTION_BUY_SELECTED"), "The detail pane should buy the selected talent.");
        assertTrue(
                page.contains("#TameworkCompanionTalentsRoot.Anchor")
                        && page.contains("resolveRootWidthForViewport(viewportWidth)")
                        && page.contains("#TalentTreeViewport.Anchor")
                        && page.contains("resolveViewportWidthForContent(canvas.width())"),
                "Talent tree root and viewport widths should scale with the rendered tree canvas."
        );
        assertFalse(
                page.contains(".Background\", resolve"),
                "Talent tree nodes and connectors should not set runtime string backgrounds that can resolve as missing textures."
        );
        assertFalse(page.contains("ACTION_PREV"), "Talent tree UI should not use previous-page actions.");
        assertFalse(page.contains("ACTION_NEXT"), "Talent tree UI should not use next-page actions.");
        assertFalse(ui.contains("TameworkCompanionTalentsPageIndicator"), "Talent tree UI should not show a page indicator.");
    }

    @Test
    void talentPageProvidesHorizontalPanControlsForWideTrees() throws IOException {
        String page = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        String ui = Files.readString(
                Path.of("src", "main", "resources", "Common", "UI", "Custom", "TameworkCompanionTalentsPage.ui"),
                StandardCharsets.UTF_8
        );

        assertTrue(page.contains("ACTION_PAN_LEFT"), "Wide talent trees should expose a left pan action.");
        assertTrue(page.contains("ACTION_PAN_RIGHT"), "Wide talent trees should expose a right pan action.");
        assertTrue(page.contains("horizontalOffset"), "The talent page should preserve horizontal tree position.");
        assertTrue(page.contains("Math.max(canvas.width() - viewportWidth, 0)"), "Pan bounds should use canvas overflow.");
        assertTrue(page.contains("buildAnchor(-horizontalOffset"), "The canvas should shift horizontally when panned.");
        assertTrue(ui.contains("#TalentTreePanLeftButton"), "Talent page should include a left pan button.");
        assertTrue(ui.contains("#TalentTreePanRightButton"), "Talent page should include a right pan button.");
    }

    @Test
    void talentPageShowsPanControlsAboveTreeAsButtonLabelButtonStrip() throws IOException {
        String page = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);
        String ui = Files.readString(
                Path.of("src", "main", "resources", "Common", "UI", "Custom", "TameworkCompanionTalentsPage.ui"),
                StandardCharsets.UTF_8
        );
        int headerStart = ui.indexOf("Group #TameworkCompanionTalentsHeader");
        int bodyStart = ui.indexOf("Group #TameworkCompanionTalentsBody");
        int panStart = ui.indexOf("Group #TalentTreePanControls");
        int leftStart = ui.indexOf("#TalentTreePanLeftButton", panStart);
        int labelStart = ui.indexOf("#TalentTreePanLabel", panStart);
        int rightStart = ui.indexOf("#TalentTreePanRightButton", panStart);

        assertTrue(headerStart >= 0, "Talent page should have a header.");
        assertTrue(bodyStart > headerStart, "Talent page body should follow the header.");
        assertTrue(panStart > headerStart && panStart < bodyStart, "Pan controls should sit in the header above the tree.");
        assertTrue(leftStart > panStart, "Pan strip should start with a left button.");
        assertTrue(labelStart > leftStart, "Pan strip should show a label after the left button.");
        assertTrue(rightStart > labelStart, "Pan strip should end with a right button.");
        assertTrue(ui.contains("Label #TalentTreePanLabel"), "Pan strip should include a non-button label.");
        assertTrue(ui.contains("Text: \"Pan\""), "Pan strip label should read Pan.");
        assertTrue(page.contains("#TalentTreePanLeftButton.Visible\", maxHorizontalOffset > 0"), "Left pan button should show whenever overflow controls are needed.");
        assertTrue(page.contains("#TalentTreePanRightButton.Visible\", maxHorizontalOffset > 0"), "Right pan button should show whenever overflow controls are needed.");
    }

    @Test
    void talentPanControlsCenterOverDynamicTreeViewport() throws IOException {
        String page = Files.readString(TALENTS_PAGE, StandardCharsets.UTF_8);

        assertTrue(
                page.contains("#TalentTreePanControls.Anchor")
                        && page.contains("resolvePanControlsAnchor(viewportWidth)"),
                "Pan controls should bind their anchor from the current viewport width."
        );
        assertEquals(296, TameworkCompanionTalentsPage.resolvePanControlsLeft(704));
        assertEquals(354, TameworkCompanionTalentsPage.resolvePanControlsLeft(820));
        assertEquals(0, TameworkCompanionTalentsPage.resolvePanControlsLeft(80));
    }
}
