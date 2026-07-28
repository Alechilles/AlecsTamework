package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression coverage for the dedicated final bonded-companion card states. */
class BondedCompanionCardPresenterTest {
    @Test
    void dedicatedCardAssetContainsEveryFinalStateControl() throws Exception {
        String asset = Files.readString(Path.of("src", "main", "resources",
                "Common", "UI", "Custom",
                "TameworkBondedCompanionPanelCard.ui"), StandardCharsets.UTF_8);

        assertTrue(asset.contains("#BondedAccentInWorld"));
        assertTrue(asset.contains("#BondedAccentStored"));
        assertTrue(asset.contains("#BondedAccentDead"));
        assertTrue(asset.contains("#BondedAccentReady"));
        assertTrue(asset.contains("#BondedStateInWorld"));
        assertTrue(asset.contains("#BondedStateStored"));
        assertTrue(asset.contains("#BondedStateDead"));
        assertTrue(asset.contains("#BondedStateReady"));
        assertFalse(asset.contains("Group #BondedStateInWorld"),
                "Lifecycle state is informational text, not a button-like badge.");
        assertTrue(asset.contains("#BondedHealthFrame"));
        assertTrue(asset.contains("#BondedHealthFill")
                        && asset.contains("Top: 1, Left: 1, Width: 359, Height: 16"),
                "The static health fill begins inside the track; runtime sizing preserves its right inset.");
        assertTrue(asset.contains("Height: 18") && asset.contains("FontSize: 11"),
                "Health treatment should be easier to read than the compact original.");
        assertTrue(asset.contains("#BondedMetricHappiness"));
        assertTrue(asset.contains("#BondedMetricHunger"));
        assertTrue(asset.contains("#BondedMetricThirst"));
        assertTrue(asset.contains("#BondedProgressionButton"));
        assertTrue(asset.contains("#BondedLevelText"));
        assertTrue(asset.contains("#BondedTalentPointAction"));
        assertTrue(asset.contains("TalentPoint_UpArrow.png"));
        assertFalse(asset.contains("#BondedTalentAction"),
                "Progression belongs in the identity row, not a separate card action.");
        assertTrue(asset.contains("#BondedPrimaryActionDisabled"));
        assertTrue(asset.contains("#BondedPrimaryActionNoTooltip"));
        assertTrue(asset.contains("#BondedPrimaryActionDisabledNoTooltip"));
        assertTrue(asset.contains("#BondedReviveAction"));
        assertTrue(asset.contains("#BondedReviveActionNoTooltip"));
        assertTrue(asset.contains("#BondedUnlinkButton"));
        assertTrue(asset.contains("#BondedUnlinkConfirmButton"));
        assertFalse(asset.contains("#BondedCostList"));
        assertTrue(asset.contains("#BondedReviveAction")
                        && asset.contains("TextTooltipStyle: @BondedCardTextTooltipStyle;"),
                "Revive costs should be available from the compact action tooltip.");
    }

    @Test
    void activeCardShowsOnlyConfiguredMetricsAndItsDismissAction() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS,
                true,
                Map.of(
                        "currentHealth", "320.0",
                        "maxHealth", "400.0",
                        "happiness", "0.80",
                        "level", "12",
                        "levelingConfigId", "TwLevelingDefault",
                        "talentConfigId", "TwTalentsExample",
                        "talentSpentPoints", "3"
                ),
                null
        );
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(
                commands, new UIEventBuilder(), "#Card", UUID.randomUUID(),
                row, false, bindingConfig(), "en-US"
        );

        assertCommand(commands, "#Card #BondedStateInWorld.Text", "IN WORLD");
        assertCommand(commands, "#Card #BondedStateDetail.Text", "SUMMONED");
        assertCommand(commands, "#Card #BondedStateDetailValue.Text", "AT YOUR SIDE");
        assertCommand(commands, "#Card #BondedPrimaryAction.Text", "DISMISS");
        assertCommand(commands, "#Card #BondedHealthText.Text", "320 / 400");
        assertCommand(commands, "#Card #BondedMetricHappiness.Visible", "true");
        assertCommand(commands, "#Card #BondedMetricHappiness #MetricValue.Text", "1%");
        assertCommand(commands, "#Card #BondedMetricHunger.Visible", "false");
        assertCommand(commands, "#Card #BondedMetricThirst.Visible", "false");
        assertCommand(commands, "#Card #BondedSpecies.Text", "Nordic Drake");
        assertCommand(commands, "#Card #BondedLevelText.Text", "LVL 12");
        assertCommand(commands, "#Card #BondedProgressionButton.Visible", "true");
        assertCommand(commands, "#Card #BondedProgressionButton.TooltipText",
                "Level: 12");
    }

    @Test
    void storedCooldownDoesNotClaimTheCompanionIsReadyToSummon() {
        BondedCompanionPanelPresentation row = presentation(
                new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON,
                        false, BondedCompanionActionBlockReason.COOLDOWN_ACTIVE,
                        null, 60_000L), Map.of(), null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedStateStored.Text", "STORED");
        assertCommand(commands, "#Card #BondedStateDetail.Text", "SUMMON AVAILABLE IN");
        assertCommand(commands, "#Card #BondedStateDetailValue.Text", "1m");
    }

    @Test
    void disabledSummonHidesGenericCardTextAndExplainsCapacityOnHover() {
        BondedCompanionPanelPresentation row = presentation(
                new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON,
                        false, BondedCompanionActionBlockReason.CAPACITY_REACHED,
                        null, 0L),
                Map.of(
                        "bonded.activeCapacity.count", "1",
                        "bonded.activeCapacity.limit", "1",
                        "bonded.activeCapacity.label", "Full Dragons"
                ), null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedStateDetail.Text", "\"\"");
        assertCommand(commands, "#Card #BondedPrimaryActionDisabled.TooltipText",
                "Max Nordic Drakes already summoned (1/1)");
        assertCommand(commands, "#Card #BondedPrimaryActionDisabled.Visible", "true");
        assertCommand(commands, "#Card #BondedPrimaryActionDisabledNoTooltip.Visible", "false");
    }

    @Test
    void compactStoredCardUpdatesTheStateDetailAndSummonIntoOneActionRow() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON,
                true, Map.of(), null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommandSelector(commands, "#Card.Anchor");
        assertCommandSelector(commands, "#Card #BondedStateDetail.Anchor");
        assertCommand(commands, "#Card #BondedStateDetail.Anchor", "20");
        assertCommand(commands, "#Card #BondedStateDetailValue.Anchor", "20");
        assertCommandSelector(commands, "#Card #BondedPrimaryAction.Anchor");
        assertCommand(commands, "#Card #BondedPrimaryAction.Visible", "false");
        assertCommand(commands, "#Card #BondedPrimaryActionNoTooltip.Visible", "true");
    }

    @Test
    void unavailableReviveKeepsTheDeadStateInsteadOfShowingReady() {
        BondedCompanionPanelPresentation row = presentation(
                new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.DEAD,
                        BondedCompanionStatusPresentation.Action.REVIVE,
                        false, BondedCompanionActionBlockReason.PAYMENT_UNAVAILABLE,
                        null, 0L), Map.of(), new BondedCompanionReviveQuote(
                        "profile-7", true, List.of(), 0L, 0L));
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedStateDead.Text", "DEAD");
        assertCommand(commands, "#Card #BondedAccentReady.Visible", "false");
    }

    @Test
    void pendingUnlinkReplacesNormalActionsWithPermanentDeleteConfirmation() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS,
                true, Map.of(), null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, true, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedUnlinkButton.Visible", "false");
        assertCommand(commands, "#Card #BondedUnlinkConfirmButton.Visible", "true");
        assertCommand(commands, "#Card #BondedPrimaryAction.Visible", "false");
        assertCommand(commands, "#Card #BondedStateDetail.Text",
                "DELETE THIS COMPANION PERMANENTLY");
    }

    @Test
    void deadCooldownCardKeepsReviveCostInTheActionTooltip() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.DEAD,
                BondedCompanionStatusPresentation.Action.REVIVE,
                false,
                Map.of("currentHealth", "0", "maxHealth", "400"),
                new BondedCompanionReviveQuote(
                        "profile-7", true, List.of(
                        new BondedCompanionReviveQuote.CostLine(
                                "Ingredient_Life_Essence", 2, 1),
                        new BondedCompanionReviveQuote.CostLine(
                                "Ingredient_Amber", 4, 4)
                ), 272L, 4L)
        );
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(
                commands, new UIEventBuilder(), "#Card", UUID.randomUUID(),
                row, false, bindingConfig(), "en-US"
        );

        assertCommand(commands, "#Card #BondedStateDead.Text", "DEAD");
        assertCommand(commands, "#Card #BondedPrimaryActionDisabled.Visible", "true");
        assertCommand(commands, "#Card #BondedPrimaryActionDisabled.Text", "REVIVE");
        assertCommand(commands, "#Card #BondedPrimaryActionDisabled.TooltipText",
                "REVIVE COST");
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> command.selector.contains("BondedCostList")),
                "Cost lines must not increase the compact companion card height.");
    }

    @Test
    void identityLineKeepsGenderInItsIconAndProgressionUsesSeparateSafeLabels() {
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons", "NordicDrake", 4L,
                "Wyatt", "Nordic Drake", "Female", null,
                Map.of("level", "1", "levelingConfigId", "saved-leveling",
                        "talentConfigId", "saved-talents", "talentSpentPoints", "0"),
                Map.of(), new BondedCompanionStatusPresentation(
                BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON,
                true, null, 0L), null
        );
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedSpecies.Text", "Nordic Drake");
        assertCommand(commands, "#Card #BondedLevelText.Text", "LVL 1");
        assertCommand(commands, "#Card #BondedGenderFemaleIcon.Visible", "true");
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> "#Card #BondedSpecies.Text".equals(command.selector)
                                && command.data.contains("Female")),
                "Gender belongs exclusively to the existing gender icon.");
        assertCommand(commands, "#Card #BondedTalentPointAction.Visible", "true");
        assertCommand(commands, "#Card #BondedTalentPointCount.Visible", "false");
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> "#Card #BondedLevelText.Text".equals(command.selector)
                                && command.data.contains("<color")),
                "Runtime label text is literal; colored spans must not be sent as markup.");
    }

    @Test
    void fullHealthUsesTheWholeCardHealthTrack() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON,
                true,
                Map.of("currentHealth", "400", "maxHealth", "400"), null
        );
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedHealthFill.Anchor", "359");
    }

    @Test
    void progressionRowUsesTheExistingTalentCommandPathForEveryBondedState()
            throws Exception {
        String presenter = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "ui",
                "BondedCompanionCardPresenter.java"), StandardCharsets.UTF_8);

        int progressionStart = presenter.indexOf("private static void bindProgression");
        int primaryActionStart = presenter.indexOf("private static void bindPrimaryAction");
        String progression = presenter.substring(progressionStart, primaryActionStart);
        assertTrue(progression.contains("#BondedProgressionButton"));
        assertTrue(progression.contains("config.openTalentsCommandPrefix() + cardUuid"),
                "The inline level text must open this bonded companion's talent page.");
        assertTrue(progression.contains("boolean canOpen = !pendingUnlink"),
                "The persistent stats button should open the durable talent page in every card state.");
        assertFalse(progression.contains("row.status().state() == BondedCompanionStateView.ACTIVE"),
                "Stored and dead bonded companions must open the same durable talent page.");
    }

    @Test
    void cardCanRebindTalentInputWithoutRecreatingItsVisualTree() throws Exception {
        String presenter = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "ui",
                "BondedCompanionCardPresenter.java"), StandardCharsets.UTF_8);

        int bindingStart = presenter.indexOf("static void bindEventBindings(");
        int unlinkStart = presenter.indexOf("private static void bindUnlink(", bindingStart);
        assertTrue(bindingStart >= 0,
                "Bonded cards need an input-only refresh binding helper.");
        assertTrue(unlinkStart > bindingStart,
                "Input-only binding helper should be bounded by card rendering helpers.");

        String binding = presenter.substring(bindingStart, unlinkStart);
        assertTrue(binding.contains("bindProgressionEvents"),
                "The lightweight refresh must keep the level/talent shortcut bound.");
        assertFalse(binding.contains("UICommandBuilder"),
                "Input refreshes must not rebuild or flicker the card visual tree.");
    }

    @Test
    void pointsUseTheCompactLevelUpIconInsteadOfLongInlineText() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON,
                true,
                Map.of("level", "4", "levelingConfigId", "missing-config",
                        "talentConfigId", "saved-talents", "talentSpentPoints", "1"),
                null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedTalentPointAction.Visible", "true");
        assertCommand(commands, "#Card #BondedTalentPointCount.Visible", "true");
        assertCommand(commands, "#Card #BondedTalentPointCount.Text", "2");
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> "#Card #BondedSpecies.Text".equals(command.selector)
                                && command.data.contains("POINTS AVAILABLE")),
                "Available points should be represented by the compact icon badge.");
    }

    @Test
    void finalCardPlacesLevelInTheIdentityRowAndStatsBesideTheAction()
            throws Exception {
        String asset = Files.readString(Path.of("src", "main", "resources",
                "Common", "UI", "Custom",
                "TameworkBondedCompanionPanelCard.ui"), StandardCharsets.UTF_8);

        assertTrue(asset.contains("#BondedLevelText {\n        Anchor: (Top: 30, Left: 22, Width: 42"),
                "Level should lead the compact identity row.");
        assertTrue(asset.contains("#BondedGenderFemaleIcon {\n        Anchor: (Top: 33, Left: 68"),
                "Gender icon should follow the level text.");
        assertTrue(asset.contains("#BondedSpecies {\n        Anchor: (Top: 30, Left: 86"),
                "Species should follow the gender icon.");
        assertTrue(asset.contains("#BondedTalentPointAction {\n        Anchor: (Top: 82, Right: 118"),
                "The stats button belongs beside the primary bottom-right action.");
        assertTrue(asset.contains("#BondedHealthFrame {\n        Anchor: (Top: 56, Left: 20, Right: 24"),
                "Health should align to the primary action's right edge.");
    }

    @Test
    void bondedCardAssetHasAFullCardOutline() throws Exception {
        String asset = Files.readString(Path.of("src", "main", "resources",
                "Common", "UI", "Custom",
                "TameworkBondedCompanionPanelCard.ui"), StandardCharsets.UTF_8);

        assertTrue(asset.contains("#BondedFrameInWorld"));
        assertTrue(asset.contains("#BondedFrameStored"));
        assertTrue(asset.contains("#BondedFrameDead"));
        assertTrue(asset.contains("#BondedFrameReady"));
        assertTrue(asset.contains("OutlineColor:") && asset.contains("OutlineSize:"),
                "The companion card frame should be a subtle outline, not a second opaque panel.");
    }

    private static BondedCompanionPanelPresentation presentation(
            BondedCompanionStateView state,
            BondedCompanionStatusPresentation.Action action,
            boolean actionEnabled,
            Map<String, String> attributes,
            BondedCompanionReviveQuote quote
    ) {
        return presentation(new BondedCompanionStatusPresentation(
                state, action, actionEnabled, null, 0L), attributes, quote);
    }

    private static BondedCompanionPanelPresentation presentation(
            BondedCompanionStatusPresentation status,
            Map<String, String> attributes,
            BondedCompanionReviveQuote quote
    ) {
        return new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons", "NordicDrake", 4L,
                "Bonded Nordic Drake", "Nordic Drake", "Male", null,
                attributes, Map.of(), status, quote
        );
    }

    private static LinkedNpcPanelCardBinder.CardBindingConfig bindingConfig() {
        return new LinkedNpcPanelCardBinder.CardBindingConfig(
                "card.ui", "Command", "link:", "unlink:", "group:",
                "active:", "breed:", "release:", "cull:", "respawn:",
                "summon:", "dismiss:", "locate:", "recall:", "home:",
                "return:", "talents:", true, true);
    }

    private static void assertCommand(
            UICommandBuilder commands, String selector, String expected
    ) {
        assertTrue(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)
                                && command.data.contains(expected)),
                () -> "Expected " + selector + " to contain " + expected);
    }

    private static void assertCommandSelector(
            UICommandBuilder commands, String selector
    ) {
        assertTrue(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)),
                () -> "Expected a command for " + selector);
    }
}
