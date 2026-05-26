package com.alechilles.alecstamework.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards linked-panel progression controls against being anchored outside their card.
 */
class LinkedNpcPanelCardLayoutTest {
    private static final Path CARD_UI = Paths.get(
            "src", "main", "resources", "Common", "UI", "Custom", "TameworkLinkedNpcPanelCard.ui"
    );
    private static final Path CARD_BINDER = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework", "ui", "LinkedNpcPanelCardBinder.java"
    );
    private static final Pattern FUTURE_ACTION_BAR_ANCHOR = Pattern.compile(
            "Group #FutureActionBar \\{\\s*Anchor: \\(Top: (\\d+),[^)]*Height: (\\d+)\\);",
            Pattern.MULTILINE
    );
    private static final Pattern EXPANDED_CARD_HEIGHT = Pattern.compile(
            "EXPANDED_CARD_HEIGHT\\s*=\\s*(\\d+)"
    );

    @Test
    void expandedLinkedPanelCardContainsProgressionRows() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);

        Matcher actionBar = FUTURE_ACTION_BAR_ANCHOR.matcher(cardUi);
        Matcher expandedHeight = EXPANDED_CARD_HEIGHT.matcher(binder);

        assertTrue(actionBar.find(), "FutureActionBar anchor must stay parseable by the layout guard.");
        assertTrue(expandedHeight.find(), "LinkedNpcPanelCardBinder must define EXPANDED_CARD_HEIGHT.");

        int actionBottom = Integer.parseInt(actionBar.group(1)) + Integer.parseInt(actionBar.group(2));
        int cardHeight = Integer.parseInt(expandedHeight.group(1));

        assertTrue(
                cardHeight >= actionBottom,
                () -> "Expanded linked-panel card height " + cardHeight
                        + " clips progression controls ending at " + actionBottom + "."
        );
    }
}
