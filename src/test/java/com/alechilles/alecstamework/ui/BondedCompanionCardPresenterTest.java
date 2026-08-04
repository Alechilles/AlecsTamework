package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
                        && asset.contains("Top: 1, Left: 1, Width: 358, Height: 16"),
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
    void progressionTooltipAndXpStripUseTheSavedLevelingConfig() throws Exception {
        String presenter = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "ui",
                "BondedCompanionCardPresenter.java"), StandardCharsets.UTF_8);
        String binder = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "ui",
                "LinkedNpcPanelProgressionBinder.java"), StandardCharsets.UTF_8);

        assertTrue(presenter.contains("bindXpProgress(commands, entrySelector, progression, row.attributes())"));
        assertTrue(presenter.contains("#BondedXpFill.Anchor"));
        assertTrue(presenter.contains("resolveLevelBonusTooltip("));
        assertTrue(binder.contains("Level Bonuses")
                        && binder.contains("effect.getPerLevel() * levelOffset"),
                "The saved config's per-level effects must become tooltip bonuses.");
    }

    @Test
    void xpStripFitsAboveHealthWithoutMovingTheExistingCardLayout() throws Exception {
        String asset = Files.readString(Path.of("src", "main", "resources",
                "Common", "UI", "Custom",
                "TameworkBondedCompanionPanelCard.ui"), StandardCharsets.UTF_8);

        assertTrue(asset.contains("Anchor: (Top: 3, Left: 0, Right: 0, Height: 118)"));
        assertTrue(selectorBlock(asset, "#BondedXpFrame")
                        .contains("Anchor: (Top: 52, Left: 20, Right: 25, Height: 3)"),
                "The thin XP strip must use the existing gap above the health frame.");
        assertTrue(selectorBlock(asset, "#BondedXpFill")
                        .contains("Anchor: (Top: 0, Left: 0, Width: 358, Height: 3)"));
        assertTrue(selectorBlock(asset, "#BondedHealthFrame")
                        .contains("Anchor: (Top: 56, Left: 20, Right: 25, Height: 18)"));
        assertTrue(selectorBlock(asset, "#BondedMetricHappiness")
                        .contains("Anchor: (Top: 82, Left: 14, Width: 66, Height: 18)"));
        assertTrue(selectorBlock(asset, "#BondedPrimaryAction")
                        .contains("Anchor: (Top: 82, Right: 14, Width: 94, Height: 28)"));
    }

    @Test
    void activeAvailableFlightToggleShowsTheGroundedIconAndFlightTooltip() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS,
                true, Map.of(
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE, "false"),
                null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedFlightToggleButton.Visible", "true");
        assertCommand(commands, "#Card #BondedFlightModeGroundedIcon.Visible", "true");
        assertCommand(commands, "#Card #BondedFlightModeAirborneIcon.Visible", "false");
        assertCommand(commands, "#Card #BondedFlightToggleButton.TooltipText",
                "Switch to flight");
    }

    @Test
    void activeAvailableFlightToggleShowsTheAirborneIconAndGroundTooltip() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS,
                true, Map.of(
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE, "true"),
                null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedFlightToggleButton.Visible", "true");
        assertCommand(commands, "#Card #BondedFlightModeGroundedIcon.Visible", "false");
        assertCommand(commands, "#Card #BondedFlightModeAirborneIcon.Visible", "true");
        assertCommand(commands, "#Card #BondedFlightToggleButton.TooltipText",
                "Switch to ground");
    }

    @Test
    void flightToggleHidesForStoredDeadDisabledAndUnreadableRows() {
        for (BondedCompanionPanelPresentation row : List.of(
                presentation(BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON, true,
                        Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true"), null),
                presentation(BondedCompanionStateView.DEAD,
                        BondedCompanionStatusPresentation.Action.REVIVE, true,
                        Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true"), null),
                presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true,
                        Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "false"), null),
                presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true,
                        Map.of(), null))) {
            UICommandBuilder commands = new UICommandBuilder();
            BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                    "#Card", UUID.randomUUID(), row, false, bindingConfig(), "en-US");
            assertCommand(commands, "#Card #BondedFlightToggleButton.Visible", "false");
            assertCommand(commands, "#Card #BondedFlightModeGroundedIcon.Visible", "false");
            assertCommand(commands, "#Card #BondedFlightModeAirborneIcon.Visible", "false");
        }
    }

    @Test
    void dynamicRefreshUpdatesFlightToggleWithoutRecreatingTheCard() {
        UICommandBuilder commands = new UICommandBuilder();
        BondedCompanionCardPresenter.refreshDynamicState(commands, "#Card", presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE, "true"), null), "en-US");

        assertCommand(commands, "#Card #BondedFlightModeGroundedIcon.Visible", "false");
        assertCommand(commands, "#Card #BondedFlightModeAirborneIcon.Visible", "true");
        assertCommand(commands, "#Card #BondedFlightToggleButton.TooltipText",
                "Switch to ground");
    }

    @Test
    void flightToggleAssetAndIconsHaveTheFinalSelectorsTexturesAndTransparency()
            throws Exception {
        String asset = Files.readString(Path.of("src", "main", "resources", "Common",
                "UI", "Custom", "TameworkBondedCompanionPanelCard.ui"), StandardCharsets.UTF_8);
        String name = selectorBlock(asset, "#BondedName");
        String grounded = selectorBlock(asset, "#BondedFlightModeGroundedIcon");
        String airborne = selectorBlock(asset, "#BondedFlightModeAirborneIcon");
        String button = selectorBlock(asset, "#BondedFlightToggleButton");
        assertTrue(name.contains("Right: 142"));
        assertTrue(grounded.contains("Top: 5, Right: 108, Width: 24, Height: 24")
                && grounded.contains("Tamework/LinkedPanelIcons/FlightMode_Grounded.png")
                && grounded.contains("Visible: false"));
        assertTrue(airborne.contains("Top: 5, Right: 108, Width: 24, Height: 24")
                && airborne.contains("Tamework/LinkedPanelIcons/FlightMode_Airborne.png")
                && airborne.contains("Visible: false"));
        assertTrue(button.contains("Top: 5, Right: 108, Width: 24, Height: 24")
                && button.contains("Style: @BondedTransparentButton")
                && button.contains("Text: \"\"")
                && button.contains("TooltipText: \"\"")
                && button.contains("TextTooltipStyle: @BondedCardTextTooltipStyle")
                && button.contains("Visible: false"));
        assertTrue(asset.indexOf("#BondedFlightModeGroundedIcon")
                        < asset.indexOf("#BondedFlightModeAirborneIcon")
                        && asset.indexOf("#BondedFlightModeAirborneIcon")
                        < asset.indexOf("#BondedFlightToggleButton"),
                "The transparent click target must be declared over both icons.");
        assertIconIs32RgbaWithTransparency("FlightMode_Grounded.png");
        assertIconIs32RgbaWithTransparency("FlightMode_Airborne.png");
    }

    private static void assertIconIs32RgbaWithTransparency(String fileName) throws Exception {
        BufferedImage image = javax.imageio.ImageIO.read(new File("src/main/resources/Common/UI/Custom/Tamework/LinkedPanelIcons", fileName));
        assertTrue(image != null && image.getWidth() == 32 && image.getHeight() == 32);
        assertTrue(image.getColorModel().hasAlpha());
        boolean transparent = false;
        boolean visible = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                transparent |= alpha < 255;
                visible |= alpha > 0;
            }
        }
        assertTrue(transparent && visible, "Icon must have transparent padding and visible art.");
    }

    @Test
    void flightToggleUsesTheProfileScopedCommandPrefix() throws Exception {
        String presenter = Files.readString(Path.of("src", "main", "java",
                "com", "alechilles", "alecstamework", "ui",
                "BondedCompanionCardPresenter.java"), StandardCharsets.UTF_8);
        assertTrue(presenter.contains("config.bondedFlightToggleCommandPrefix() + cardUuid"));
        assertTrue(presenter.contains("if (flightToggleVisible(row))"));
    }

    @Test
    void lightweightRefreshBindsOnlyEligibleFlightToggleWithItsProfileUuid() {
        UUID cardUuid = UUID.randomUUID();
        BondedCompanionPanelPresentation eligible = presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true"),
                null);
        UIEventBuilder eligibleEvents = new UIEventBuilder();

        BondedCompanionCardPresenter.bindEventBindings(eligibleEvents, "#Card",
                cardUuid, eligible, false, bindingConfig(), "en-US");

        assertTrue(java.util.Arrays.stream(eligibleEvents.getEvents()).anyMatch(event ->
                        event.type == CustomUIEventBindingType.Activating
                                && "#Card #BondedFlightToggleButton".equals(event.selector)
                                && event.data.contains("__bonded_flight_toggle__:" + cardUuid)),
                "Each eligible lightweight refresh must rebind this profile's flight toggle.");

        UIEventBuilder unavailableEvents = new UIEventBuilder();
        BondedCompanionCardPresenter.bindEventBindings(unavailableEvents, "#Card",
                cardUuid, presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true,
                        Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE,
                                "false"), null), false, bindingConfig(), "en-US");
        assertFalse(java.util.Arrays.stream(unavailableEvents.getEvents()).anyMatch(event ->
                        "#Card #BondedFlightToggleButton".equals(event.selector)),
                "Unavailable rows must not retain a stale flight-toggle event binding.");
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

        assertCommand(commands, "#Card #BondedHealthFill.Anchor", "358");
    }

    @Test
    void dynamicRefreshPatchesTheLiveHealthBarWithoutRecreatingTheCard() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS,
                true,
                Map.of("currentHealth", "125", "maxHealth", "250"), null
        );
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.refreshDynamicState(commands, "#Card", row,
                "en-US");

        assertCommand(commands, "#Card #BondedHealthText.Text", "125 / 250");
        assertCommand(commands, "#Card #BondedHealthFill.Anchor", "179");
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
        assertTrue(binding.contains("bindFlightToggleEvents"),
                "The lightweight refresh must keep an eligible flight toggle bound.");
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

        assertTrue(selectorBlock(asset, "#BondedLevelText")
                        .contains("Anchor: (Top: 30, Left: 22, Width: 42"),
                "Level should lead the compact identity row.");
        assertTrue(selectorBlock(asset, "#BondedGenderFemaleIcon")
                        .contains("Anchor: (Top: 33, Left: 68"),
                "Gender icon should follow the level text.");
        assertTrue(selectorBlock(asset, "#BondedSpecies")
                        .contains("Anchor: (Top: 30, Left: 86"),
                "Species should follow the gender icon.");
        assertTrue(selectorBlock(asset, "#BondedTalentPointAction")
                        .contains("Anchor: (Top: 82, Right: 118"),
                "The stats button belongs beside the primary bottom-right action.");
        assertTrue(selectorBlock(asset, "#BondedHealthFrame")
                        .contains("Anchor: (Top: 56, Left: 20, Right: 25"),
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

    private static String selectorBlock(String asset, String selector) {
        Matcher matcher = Pattern.compile("(?m)^\\s*(?:[A-Za-z]+\\s+)?"
                + Pattern.quote(selector) + "\\s*\\{").matcher(asset);
        assertTrue(matcher.find(), () -> "Expected selector block " + selector);
        int start = matcher.start();
        int cursor = matcher.end();
        int depth = 1;
        while (cursor < asset.length() && depth > 0) {
            char character = asset.charAt(cursor++);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
        }
        assertTrue(depth == 0, () -> "Unclosed selector block " + selector);
        return asset.substring(start, cursor);
    }

    private static void assertCommand(
            UICommandBuilder commands, String selector, String expected
    ) {
        assertTrue(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)
                                && command.data.contains(expected)),
                () -> "Expected " + selector + " to contain " + expected
                        + "; actual data: " + java.util.Arrays.stream(commands.getCommands())
                        .filter(command -> selector.equals(command.selector))
                        .map(command -> command.data)
                        .toList());
    }

    private static void assertCommandSelector(
            UICommandBuilder commands, String selector
    ) {
        assertTrue(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)),
                () -> "Expected a command for " + selector);
    }

}
