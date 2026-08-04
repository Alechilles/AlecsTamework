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
    private static final Path PROGRESSION_BINDER = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework", "ui", "LinkedNpcPanelProgressionBinder.java"
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
    void compactLinkedPanelCardContainsProgressionControls() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);
        String progressionBinder = Files.readString(PROGRESSION_BINDER, StandardCharsets.UTF_8);

        Matcher normalCardHeight = NORMAL_CARD_HEIGHT.matcher(binder);
        Matcher xpRing = XP_RING_ANCHOR.matcher(cardUi);
        Matcher talentPoint = TALENT_POINT_ANCHOR.matcher(cardUi);
        Matcher talentPointBadge = TALENT_POINT_BADGE_BORDER_ANCHOR.matcher(cardUi);

        assertTrue(normalCardHeight.find(), "LinkedNpcPanelCardBinder must define its normal card height.");
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
                progressionBinder.contains("Integer.toString(availableTalentPoints(stat))"),
                "Talent point badge should show the compact count without a plus prefix."
        );
        assertTrue(
                cardUi.contains("@LinkedProgressionTooltipStyle = TextTooltipStyle")
                        && cardUi.contains("MaxWidth: 360")
                        && cardUi.contains("TextTooltipStyle: @LinkedProgressionTooltipStyle;"),
                "XP progression tooltip should use its wider local tooltip style."
        );
        List<String> unquotedStringTextDefaults = findUnquotedStringTextDefaults(cardUi);
        assertTrue(
                unquotedStringTextDefaults.isEmpty(),
                () -> "Text or TooltipText defaults must be quoted or localized for Hytale's CustomUI parser: "
                        + unquotedStringTextDefaults
        );
        assertFalse(binder.contains("EXPANDED_CARD_HEIGHT"), "Progression controls should fit inside the compact card.");
        assertTrue(
                binder.contains("NORMAL_CARD_HEIGHT = 84")
                        && binder.contains("ROSTER_CARD_HEIGHT = 126"),
                "Normal cards should stay compact while roster rows reserve their status lane."
        );

        int parsedNormalCardHeight = Integer.parseInt(normalCardHeight.group(1));
        int xpRingLeft = Integer.parseInt(xpRing.group(2));
        int xpRingBottom = Integer.parseInt(xpRing.group(1)) + Integer.parseInt(xpRing.group(4));
        int talentPointRight = Integer.parseInt(talentPoint.group(2)) + Integer.parseInt(talentPoint.group(3));
        int talentPointBottom = Integer.parseInt(talentPoint.group(1)) + Integer.parseInt(talentPoint.group(4));
        int talentPointWidth = Integer.parseInt(talentPoint.group(3));
        int badgeTop = Integer.parseInt(talentPointBadge.group(1));
        int badgeLeft = Integer.parseInt(talentPointBadge.group(2));
        int badgeWidth = Integer.parseInt(talentPointBadge.group(3));
        int badgeHeight = Integer.parseInt(talentPointBadge.group(4));
        int badgeRight = badgeLeft + badgeWidth;

        assertTrue(
                parsedNormalCardHeight >= xpRingBottom,
                () -> "Normal linked-panel card height " + parsedNormalCardHeight
                        + " clips XP ring ending at " + xpRingBottom + "."
        );
        assertTrue(
                talentPointRight < xpRingLeft,
                () -> "Talent point action should remain left of XP ring; talent right "
                        + talentPointRight + ", XP left " + xpRingLeft + "."
        );
        assertTrue(
                parsedNormalCardHeight >= talentPointBottom,
                () -> "Normal linked-panel card height " + parsedNormalCardHeight
                        + " clips talent point action ending at " + talentPointBottom + "."
        );
        assertTrue(
                badgeTop <= 2,
                () -> "Talent point badge should stay near the top of the button; top was " + badgeTop + "."
        );
        assertTrue(
                badgeRight >= talentPointWidth,
                () -> "Talent point badge should align to the right edge of the button; right was " + badgeRight + "."
        );
        assertTrue(
                badgeWidth <= 14,
                () -> "Talent point badge should stay compact; width was " + badgeWidth + "."
        );
        assertTrue(
                badgeHeight >= 12,
                () -> "Talent point badge should preserve the original text height; height was " + badgeHeight + "."
        );
        assertTrue(
                cardUi.contains("FontSize: 8"),
                "Talent point count should preserve the original readable text size."
        );
    }

    @Test
    void deadCompanionCardShowsFreeHeartbeatRespawnWithoutCostPresentation() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);
        String binder = Files.readString(CARD_BINDER, StandardCharsets.UTF_8);
        BufferedImage heartbeat = ImageIO.read(REVIVE_HEARTBEAT_ICON.toFile());

        assertNotNull(heartbeat, "Revive heartbeat icon must be a readable PNG.");
        assertEquals(41, heartbeat.getWidth(), "Revive heartbeat should retain the supplied 41px width.");
        assertEquals(41, heartbeat.getHeight(), "Revive heartbeat should retain the supplied 41px height.");
        assertTrue(
                cardUi.contains("Anchor: (Top: 26, Right: 118, Width: 34, Height: 34);"),
                "The dead-card revive action should use the in-game-scaled heartbeat control from the mockup."
        );
        assertFalse(cardUi.contains("ReviveCost"),
                "Free respawn cards must not retain paid item-cost controls.");
        assertFalse(binder.contains("CommandReviveCostPresentation")
                        || binder.contains("bindReviveCosts"),
                "The card binder must not project paid revival quote state.");
        assertTrue(
                binder.contains("!showRespawn") && binder.contains("!entry.dead() && !entry.lost()"),
                "The visible heartbeat action should replace the inactive badge and locate action instead of overlapping them."
        );
        assertFalse(
                binder.contains("respawnSelector + \".Enabled\""),
                "TextButton has no runtime-settable Enabled markup property; binding it disconnects the client."
        );
        assertTrue(
                binder.contains("respawnSelector + \".Visible\", showRespawn"),
                "A ready free-respawn action should be shown without an unsupported Enabled binding."
        );
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
    void cooldownRingsUsePackagedTextureIcons() throws IOException {
        String cardUi = Files.readString(CARD_UI, StandardCharsets.UTF_8);

        assertTrue(
                cardUi.contains("TexturePath: \"Tamework/LinkedPanelIcons/Trait_Fertility.png\""),
                "Breeding cooldown should reuse the fertility trait icon texture."
        );
        assertTrue(
                cardUi.contains("TexturePath: \"Tamework/LinkedPanelIcons/Harvest_Cooldown.png\""),
                "Harvest cooldown should use the packaged harvest cooldown texture."
        );

        assertLinkedPanelIconSize("Trait_Fertility.png");
        assertLinkedPanelIconSize("Harvest_Cooldown.png");
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
