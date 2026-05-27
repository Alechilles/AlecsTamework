package com.alechilles.alecstamework.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final Pattern CARD_HEIGHT = Pattern.compile(
            "CARD_HEIGHT\\s*=\\s*(\\d+)"
    );
    private static final Pattern XP_RING_ANCHOR = Pattern.compile(
            "Group #XpProgressRing \\{\\s*Anchor: \\(Top: (\\d+),[^)]*Height: (\\d+)\\);",
            Pattern.MULTILINE
    );
    private static final Pattern TALENT_POINT_ANCHOR = Pattern.compile(
            "Group #TalentPointAction \\{\\s*Anchor: \\(Top: (\\d+),[^)]*Height: (\\d+)\\);",
            Pattern.MULTILINE
    );

    @Test
    void compactLinkedPanelCardContainsProgressionControls() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);

        Matcher cardHeight = CARD_HEIGHT.matcher(binder);
        Matcher xpRing = XP_RING_ANCHOR.matcher(cardUi);
        Matcher talentPoint = TALENT_POINT_ANCHOR.matcher(cardUi);

        assertTrue(cardHeight.find(), "LinkedNpcPanelCardBinder must define CARD_HEIGHT.");
        assertTrue(xpRing.find(), "XpProgressRing anchor must stay parseable by the layout guard.");
        assertTrue(talentPoint.find(), "TalentPointAction anchor must stay parseable by the layout guard.");
        assertFalse(cardUi.contains("FutureStatAFrame"), "Linked cards should not show expanded XP bars.");
        assertFalse(cardUi.contains("FutureActionBar"), "Linked cards should not show expanded talent action rows.");
        assertFalse(binder.contains("EXPANDED_CARD_HEIGHT"), "Progression controls should fit inside the compact card.");

        int parsedCardHeight = Integer.parseInt(cardHeight.group(1));
        int xpRingBottom = Integer.parseInt(xpRing.group(1)) + Integer.parseInt(xpRing.group(2));
        int talentPointBottom = Integer.parseInt(talentPoint.group(1)) + Integer.parseInt(talentPoint.group(2));

        assertTrue(
                parsedCardHeight >= xpRingBottom,
                () -> "Linked-panel card height " + parsedCardHeight
                        + " clips XP ring ending at " + xpRingBottom + "."
        );
        assertTrue(
                parsedCardHeight >= talentPointBottom,
                () -> "Linked-panel card height " + parsedCardHeight
                        + " clips talent point action ending at " + talentPointBottom + "."
        );
    }
}
