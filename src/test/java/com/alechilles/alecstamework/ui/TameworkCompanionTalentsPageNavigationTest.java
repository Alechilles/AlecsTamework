package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        int branchEnd = content.indexOf("PageData currentData = getPageData()", branchStart);

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
        int branchEnd = content.indexOf("PageData currentData = getPageData()", branchStart);
        int bindStart = content.indexOf("commandBuilder.set(\"#TameworkCompanionTalentsResetButton.Visible\"");
        int bindEnd = content.indexOf("if (totalPages > 1 && clampedPageIndex > 0)", bindStart);

        assertTrue(branchStart >= 0, "Talent reset branch should exist.");
        assertTrue(branchEnd > branchStart, "Talent reset branch should run before page navigation actions.");
        assertTrue(bindStart >= 0, "Talent reset button visibility should be bound.");
        assertTrue(bindEnd > bindStart, "Talent reset button binding should be bounded by page controls.");

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
}
