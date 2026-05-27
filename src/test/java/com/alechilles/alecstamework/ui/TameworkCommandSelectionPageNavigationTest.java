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
        int branchEnd = content.indexOf("if (!containsOption(commandId))", branchStart);

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
                helper.contains("cancelPendingFilterTextApply()"),
                "Opening a replacement page should cancel delayed filter writes for the old page."
        );
        assertFalse(
                helper.contains("close()"),
                "Replacement-page navigation should not close the page manager while opening the next page."
        );
    }
}
