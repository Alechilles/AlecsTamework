package com.alechilles.alecstamework.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
            "Group #XpProgressRing \\{\\s*Anchor: \\(Top: (\\d+), Left: (\\d+), Width: (\\d+), Height: (\\d+)\\);",
            Pattern.MULTILINE
    );
    private static final Pattern TALENT_POINT_ANCHOR = Pattern.compile(
            "Group #TalentPointAction \\{\\s*Anchor: \\(Top: (\\d+), Left: (\\d+), Width: (\\d+), Height: (\\d+)\\);",
            Pattern.MULTILINE
    );
    private static final Pattern TALENT_POINT_BADGE_BORDER_ANCHOR = Pattern.compile(
            "Group #TalentPointCountBadgeBorder \\{\\s*Anchor: \\(Top: (\\d+), Left: (\\d+), Width: (\\d+), Height: (\\d+)\\);",
            Pattern.MULTILINE
    );

    @Test
    void compactLinkedPanelCardContainsProgressionControls() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);

        Matcher cardHeight = CARD_HEIGHT.matcher(binder);
        Matcher xpRing = XP_RING_ANCHOR.matcher(cardUi);
        Matcher talentPoint = TALENT_POINT_ANCHOR.matcher(cardUi);
        Matcher talentPointBadge = TALENT_POINT_BADGE_BORDER_ANCHOR.matcher(cardUi);

        assertTrue(cardHeight.find(), "LinkedNpcPanelCardBinder must define CARD_HEIGHT.");
        assertTrue(xpRing.find(), "XpProgressRing anchor must stay parseable by the layout guard.");
        assertTrue(talentPoint.find(), "TalentPointAction anchor must stay parseable by the layout guard.");
        assertTrue(talentPointBadge.find(), "Talent point badge anchor must stay parseable by the layout guard.");
        assertFalse(cardUi.contains("FutureStatAFrame"), "Linked cards should not show expanded XP bars.");
        assertFalse(cardUi.contains("FutureActionBar"), "Linked cards should not show expanded talent action rows.");
        assertFalse(cardUi.contains("Text: +"), "Bare plus-prefixed UI text fails Hytale's CustomUI parser.");
        assertTrue(
                cardUi.contains("Group #TalentPointCountBadgeBorder"),
                "Talent point count should use a badge frame instead of floating over the icon."
        );
        assertTrue(
                cardUi.contains("Group #TalentPointCountBadgeFill"),
                "Talent point count should have a dark badge fill for contrast."
        );
        assertTrue(
                binder.contains("Integer.toString(availableTalentPoints(stat))"),
                "Talent point badge should show the compact count without a plus prefix."
        );
        List<String> unquotedStringTextDefaults = findUnquotedStringTextDefaults(cardUi);
        assertTrue(
                unquotedStringTextDefaults.isEmpty(),
                () -> "Text or TooltipText defaults must be quoted or localized for Hytale's CustomUI parser: "
                        + unquotedStringTextDefaults
        );
        assertFalse(binder.contains("EXPANDED_CARD_HEIGHT"), "Progression controls should fit inside the compact card.");

        int parsedCardHeight = Integer.parseInt(cardHeight.group(1));
        int xpRingLeft = Integer.parseInt(xpRing.group(2));
        int xpRingBottom = Integer.parseInt(xpRing.group(1)) + Integer.parseInt(xpRing.group(4));
        int talentPointRight = Integer.parseInt(talentPoint.group(2)) + Integer.parseInt(talentPoint.group(3));
        int talentPointBottom = Integer.parseInt(talentPoint.group(1)) + Integer.parseInt(talentPoint.group(4));
        int talentPointWidth = Integer.parseInt(talentPoint.group(3));
        int badgeTop = Integer.parseInt(talentPointBadge.group(1));
        int badgeLeft = Integer.parseInt(talentPointBadge.group(2));
        int badgeWidth = Integer.parseInt(talentPointBadge.group(3));

        assertTrue(
                parsedCardHeight >= xpRingBottom,
                () -> "Linked-panel card height " + parsedCardHeight
                        + " clips XP ring ending at " + xpRingBottom + "."
        );
        assertTrue(
                talentPointRight < xpRingLeft,
                () -> "Talent point action should remain left of XP ring; talent right "
                        + talentPointRight + ", XP left " + xpRingLeft + "."
        );
        assertTrue(
                parsedCardHeight >= talentPointBottom,
                () -> "Linked-panel card height " + parsedCardHeight
                        + " clips talent point action ending at " + talentPointBottom + "."
        );
        assertTrue(
                badgeTop <= 2,
                () -> "Talent point badge should stay near the top of the button; top was " + badgeTop + "."
        );
        assertTrue(
                badgeLeft >= talentPointWidth / 2,
                () -> "Talent point badge should sit on the right side of the button; left was " + badgeLeft + "."
        );
        assertTrue(
                badgeWidth <= 12,
                () -> "Talent point badge should stay compact; width was " + badgeWidth + "."
        );
    }

    private static List<String> findUnquotedStringTextDefaults(String cardUi) {
        List<String> matches = new ArrayList<>();
        String[] lines = cardUi.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("Text:") && !trimmed.startsWith("TooltipText:")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            int semicolon = trimmed.lastIndexOf(';');
            if (colon < 0 || semicolon <= colon) {
                continue;
            }
            String value = trimmed.substring(colon + 1, semicolon).trim();
            if (!value.startsWith("\"") && !value.startsWith("%")) {
                matches.add((i + 1) + ": " + trimmed);
            }
        }
        return matches;
    }
}
