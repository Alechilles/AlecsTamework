package com.alechilles.alecstamework.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private static final Path PANEL_UI = Paths.get(
            "src", "main", "resources", "Common", "UI", "Custom", "TameworkLinkedNpcPanel.ui"
    );
    private static final Path REVIVE_COST_LINE_UI = Paths.get(
            "src", "main", "resources", "Common", "UI", "Custom", "TameworkReviveCostLine.ui"
    );
    private static final Pattern NORMAL_CARD_HEIGHT = Pattern.compile(
            "NORMAL_CARD_HEIGHT\\s*=\\s*(\\d+)"
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
    private static final Path LINKED_PANEL_ICONS = Paths.get(
            "src", "main", "resources", "Common", "UI", "Custom", "Tamework", "LinkedPanelIcons"
    );
    private static final Path REVIVE_HEARTBEAT_ICON = LINKED_PANEL_ICONS.resolve("Revive_Heartbeat.png");

    @Test
    void cardTextDefaultsUseClientSupportedSyntax() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        assertFalse(cardUi.contains("Text: +"), "Bare plus-prefixed text disconnects the CustomUI client.");
        assertTrue(findUnquotedStringTextDefaults(cardUi).isEmpty(),
                "CustomUI text must be quoted or localized.");
    }

    @Test
    void freeRespawnDoesNotBindUnsupportedEnabledProperty() throws IOException {
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);
        assertFalse(binder.contains("respawnSelector + \".Enabled\""),
                "TextButton.Enabled is unsupported and disconnects the CustomUI client.");
    }

    @Test
    void rosterRowsExposeStatusCapacityAndTimedActions() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);
        String featureBinder = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles",
                "alecstamework", "ui", "LinkedNpcPanelFeatureBinder.java"
        ), StandardCharsets.UTF_8);

        assertTrue(cardUi.contains("Label #RosterState"));
        assertTrue(cardUi.contains("Label #RosterTimer"));
        assertTrue(cardUi.contains("Label #RosterCapacity"));
        assertTrue(cardUi.contains("TextButton #RosterSummonButton"));
        assertTrue(cardUi.contains("TextButton #RosterDismissButton"));
        assertTrue(binder.contains("LinkedNpcPanelFeatureBinder.bind("));
        assertTrue(featureBinder.contains("roster.summonEnabled()"));
        assertTrue(featureBinder.contains("roster.dismissEnabled()"));
        assertFalse(
                featureBinder.contains("+ \".Enabled\""),
                "TextButton state should be enforced by event binding because runtime Enabled writes disconnect CustomUI."
        );
    }

    @Test
    void paidRevivalOverlayRendersEveryQuotedCostBeforeConfirmation()
            throws IOException {
        String panelUi = Files.readString(
                PANEL_UI, StandardCharsets.UTF_8
        );
        String costLineUi = Files.readString(
                REVIVE_COST_LINE_UI, StandardCharsets.UTF_8
        );
        String overlay = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles",
                "alecstamework", "ui",
                "LinkedNpcPanelReviveOverlayState.java"
        ), StandardCharsets.UTF_8);

        assertTrue(panelUi.contains(
                "Group #TameworkLinkedPanelReviveOverlay"
        ));
        assertTrue(panelUi.contains("Group #TameworkLinkedPanelReviveCostList"));
        assertTrue(panelUi.contains(
                "#TameworkLinkedPanelReviveConfirmButton"
        ));
        assertTrue(panelUi.contains(
                "#TameworkLinkedPanelReviveBlockedButton"
        ));
        assertTrue(costLineUi.contains("ItemGrid #CostItem"));
        assertTrue(panelUi.contains("Group #TameworkLinkedPanelReviveActions"));
        assertFalse(panelUi.contains("#TameworkLinkedPanelReviveSubtitle"));
        assertFalse(panelUi.contains("#TameworkLinkedPanelReviveSummary"));
        assertFalse(panelUi.contains("revive.costHeader"));
        assertTrue(costLineUi.contains("@ReviveCostItemGridStyle")
                        && costLineUi.contains("Style: @ReviveCostItemGridStyle;"),
                "Revive-cost items need an explicit ItemGrid style so their icons render.");
        assertTrue(costLineUi.contains("Anchor: (Top: 0, Left: 47, Width: 178, Height: 44);"));
        assertTrue(costLineUi.contains("Anchor: (Top: 0, Right: 9, Width: 82, Height: 44);"));
        assertFalse(costLineUi.contains("#CostShortage"));
        assertTrue(costLineUi.contains("Label #CostSatisfied"));
        assertTrue(costLineUi.contains("Label #CostInsufficient"));
        assertTrue(overlay.contains(
                "for (int index = 0; index < costs.size(); index++)"
        ));
        assertTrue(overlay.contains("COST_LINE_UI_PATH"));
        assertTrue(overlay.contains("presentation.confirmEnabled()"));
    }

    @Test
    void levelIndicatorCanOpenTalentsWithoutSpendableTalentPoints() throws IOException {
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);
        int levelActionStart = binder.indexOf("boolean canOpenTalentsFromLevelIndicator");
        int talentPointActionStart = binder.indexOf("boolean showTalentPointAction");
        int bindingStart = binder.indexOf("if (canOpenTalentsFromLevelIndicator)");
        int talentPointBindingStart = binder.indexOf("if (showTalentPointAction)");

        assertTrue(levelActionStart >= 0, "Linked card binder should define level-indicator talent access.");
        assertTrue(talentPointActionStart > levelActionStart, "Level-indicator talent access should be independent from the spendable-point badge.");
        assertTrue(bindingStart >= 0, "Linked card binder should bind the level indicator as an action.");
        assertTrue(talentPointBindingStart > bindingStart, "Level-indicator action should not be nested under the spendable-point action.");

        String conditionBlock = binder.substring(levelActionStart, talentPointActionStart);
        String bindingBlock = binder.substring(bindingStart, talentPointBindingStart);
        assertTrue(conditionBlock.contains("entry.isTalentsActionVisible()"), "Level-indicator action should respect talent visibility.");
        assertTrue(conditionBlock.contains("entry.isTalentsActionEnabled()"), "Level-indicator action should respect talent enablement.");
        assertTrue(conditionBlock.contains("entry.futureStatA() != null"), "Level-indicator action should only bind when the level indicator exists.");
        assertTrue(conditionBlock.contains("!pendingUnlink"), "Level-indicator action should not fire during unlink confirmation.");
        assertFalse(conditionBlock.contains("availableTalentPoints"), "Level-indicator talent access must not require spendable points.");
        assertTrue(bindingBlock.contains("xpTooltipSelector"), "Level-indicator action should use the existing XP tooltip click target.");
        assertTrue(bindingBlock.contains("config.openTalentsCommandPrefix() + entry.npcUuid()"), "Level-indicator action should open the same talent page.");
    }



    @Test
    void recoveredBreedingToggleUsesBreedingAvailabilityNotCooldownState() throws IOException {
        String entry = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "ui", "LinkedNpcEntry.java"
        ), StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);

        assertTrue(entry.contains("boolean breedingAvailable"), "LinkedNpcEntry should carry breeding availability separately.");
        assertTrue(entry.contains("public boolean breedingAvailable()"), "LinkedNpcEntry should expose breeding availability.");
        assertTrue(
                binder.contains("entry.breedingAvailable() && entry.breedingEnabled()"),
                "Enabled breeding toggle should show when breeding is available, even without an active cooldown."
        );
        assertTrue(
                binder.contains("entry.breedingAvailable() && !entry.breedingEnabled()"),
                "Disabled breeding toggle should show when breeding is available, even without an active cooldown."
        );
        assertFalse(
                binder.contains("entry.breedingCooldownKnown() && entry.breedingEnabled()"),
                "Breeding toggle visibility must not depend on cooldown snapshot availability."
        );
    }

    @Test
    void linkedPanelCardHasRecallCountdownLabel() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);

        assertTrue(
                cardUi.contains("Label #RecallCountdown"),
                "Linked card should reserve a label in the unloaded-card status area for recall countdown."
        );
        assertTrue(
                cardUi.contains("%server.tamework.ui.linkedPanel.card.recallCountdown.default"),
                "Recall countdown label should use a localized default."
        );
        assertTrue(
                binder.contains("recallCountdownSelector"),
                "Linked card binder should bind recall countdown visibility and text."
        );
        assertTrue(
                binder.contains("entry.recallPending()"),
                "Recall countdown should be driven by pending relocation state on the entry."
        );
        assertTrue(
                binder.contains("tamework.ui.linkedPanel.card.recallCountdown"),
                "Recall countdown text should use the localized countdown key."
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void assertLinkedPanelIconSize(String fileName) throws IOException {
        Path path = LINKED_PANEL_ICONS.resolve(fileName);
        assertTrue(Files.isRegularFile(path), () -> "Missing linked-panel icon: " + path);
        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image, () -> "Linked-panel icon must be a readable PNG: " + path);
        assertEquals(32, image.getWidth(), () -> "Linked-panel icon width should stay 32px: " + path);
        assertEquals(32, image.getHeight(), () -> "Linked-panel icon height should stay 32px: " + path);
    }
}
