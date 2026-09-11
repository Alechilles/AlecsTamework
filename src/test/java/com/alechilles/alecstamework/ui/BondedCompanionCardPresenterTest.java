package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
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
    void activeCardKeepsTheDismissActionWithoutPersistentNeedsMeters() {
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
        assertCommand(commands, "#Card #BondedActionLabel.Text", "DISMISS");
        assertCommand(commands, "#Card #BondedHealthText.Text", "320 / 400");
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> command.selector.contains("BondedMetric")),
                "Temporary roster summons must not render persisted needs meters.");
        assertCommand(commands, "#Card #BondedSpecies.Text", "Nordic Drake");
        assertCommand(commands, "#Card #BondedLevelText.Text", "Lv. 12");
        assertCommand(commands, "#Card #BondedProgressionButton.Visible", "true");
        assertCommand(commands, "#Card #BondedProgressionButton.TooltipText",
                "Level: 12");
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
        assertCommand(commands, "#Card #BondedFlightToggleButton.Style", "FlightGrounded");
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
        assertCommand(commands, "#Card #BondedFlightToggleButton.Style", "FlightAirborne");
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
        BondedCompanionCardPresenter.refreshDynamicState(commands, "#Card", null, presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE, "true"), null), "en-US");

        assertCommand(commands, "#Card #BondedFlightModeGroundedIcon.Visible", "false");
        assertCommand(commands, "#Card #BondedFlightToggleButton.Style", "FlightAirborne");
        assertCommand(commands, "#Card #BondedFlightToggleButton.TooltipText",
                "Switch to ground");
    }

    @Test
    void liveRefreshPreservesFlightInputWhileHealthChangesAndUpdatesFlightFeedback() {
        UUID cardUuid = UUID.randomUUID();
        BondedCompanionPanelPresentation eligible = presentation(
                BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        "currentHealth", "100", "maxHealth", "100"),
                null);
        for (boolean airborne : new boolean[] {false, true}) {
            BondedCompanionPanelPresentation current = presentation(
                    BondedCompanionStateView.ACTIVE,
                    BondedCompanionStatusPresentation.Action.DISMISS, true,
                    Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                            BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE,
                            Boolean.toString(airborne), "currentHealth", "90", "maxHealth", "100"), null);
            UICommandBuilder commands = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            LinkedNpcPanelCardDynamicPresenter.refresh(commands, events, "#Card", cardUuid,
                    null, null, CommandPanelFeaturePresentation.bonded(eligible),
                    CommandPanelFeaturePresentation.bonded(current), false, bindingConfig(), "en-US");

            assertCommand(commands, "#Card #BondedHealthText.Text", "90 / 100");
            assertTrue(events.getEvents().length == 0,
                    "Live patches must preserve existing input handlers.");
            if (airborne) {
                assertCommand(commands, "#Card #BondedFlightToggleButton.TooltipText", "Switch to ground");
            } else {
                assertFalse(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                                command.selector.contains("#BondedFlight")),
                        "Health updates must leave the flight control untouched.");
            }
        }
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
        assertCommand(commands, "#Card #BondedUnlinkCancelButton.Visible", "true");
        assertCommand(commands, "#Card #BondedActionLabel.Text", "DELETE");
    }

    @Test
    void deadCooldownCardKeepsReviveCostInTheActionTooltip() {
        BondedCompanionPanelPresentation row = presentation(
                BondedCompanionStateView.DEAD,
                BondedCompanionStatusPresentation.Action.REVIVE,
                false,
                Map.of("currentHealth", "320", "maxHealth", "400"),
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
        assertCommand(commands, "#Card #BondedActionLabel.Text", "REVIVE");
        assertCommand(commands, "#Card #BondedHealthText.Text", "0 / 400");
        assertCommand(commands, "#Card #BondedHealthFill.Visible", "false");
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
        UIEventBuilder events = new UIEventBuilder();
        UUID cardUuid = UUID.randomUUID();

        BondedCompanionCardPresenter.bind(commands, events,
                "#Card", cardUuid, row, false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedSpecies.Text", "Nordic Drake");
        assertCommand(commands, "#Card #BondedLevelText.Text", "Lv. 1");
        assertCommand(commands, "#Card #BondedGenderFemaleIcon.Visible", "true");
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> "#Card #BondedSpecies.Text".equals(command.selector)
                                && command.data.contains("Female")),
                "Gender belongs exclusively to the existing gender icon.");
        assertCommand(commands, "#Card #BondedTalentPointAction.Visible", "false");
        assertCommand(commands, "#Card #BondedTalentPointCount.Visible", "false");
        assertTrue(java.util.Arrays.stream(events.getEvents()).anyMatch(event ->
                        "#Card #BondedTalentPointButton".equals(event.selector)
                                && event.data.contains(bindingConfig().openTalentsCommandPrefix() + cardUuid)),
                "The initially hidden badge must open talents when a live level-up reveals it.");
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> "#Card #BondedLevelText.Text".equals(command.selector)
                                && command.data.contains("<color")),
                "Runtime label text is literal; colored spans must not be sent as markup.");
    }

    @Test
    void durationAndCooldownBarsShowRemainingFractionAndHideWhenReady() {
        for (BondedCompanionStateView state : BondedCompanionStateView.values()) {
            boolean active = state == BondedCompanionStateView.ACTIVE;
            var status = new BondedCompanionStatusPresentation(state,
                    active ? BondedCompanionStatusPresentation.Action.DISMISS
                            : state == BondedCompanionStateView.DEAD
                                    ? BondedCompanionStatusPresentation.Action.REVIVE
                                    : BondedCompanionStatusPresentation.Action.SUMMON,
                    active, null, active ? 0L : 40_000L);
            var row = new BondedCompanionPanelPresentation("timer", "roster", "role", 1L,
                    "Companion", null, null, null,
                    Map.of("sessionDurationMs", "80000", "sessionRemainingMs", "40000",
                            "cooldownDurationMs", "80000"), Map.of(), status, null);
            UICommandBuilder commands = new UICommandBuilder();
            BondedCompanionCardPresenter.refreshDynamicState(commands, "#Card", null, row, "en-US");
            assertCommand(commands, "#Card #BondedSessionFrame.Visible", "true");
            assertCommand(commands, "#Card #BondedSessionFill.Anchor", "191");
            assertCommand(commands, "#Card #BondedSessionFill.Visible", "true");
        }
        UICommandBuilder ready = new UICommandBuilder();
        BondedCompanionCardPresenter.refreshDynamicState(ready, "#Card", null,
                presentation(BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON, true, Map.of(), null), "en-US");
        assertCommand(ready, "#Card #BondedSessionFrame.Visible", "false");
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

        BondedCompanionCardPresenter.refreshDynamicState(commands, "#Card", null, row,
                "en-US");

        assertCommand(commands, "#Card #BondedHealthText.Text", "125 / 250");
        assertCommand(commands, "#Card #BondedHealthFill.Anchor", "129");
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

    @Test
    void shoulderRideIconUpdatesItsMountStateTooltip() {
        UICommandBuilder commands = new UICommandBuilder();
        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                "#Card", UUID.randomUUID(), presentation(
                        BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS,
                        true, Map.of(
                                BondedCompanionPresentationAttributes
                                        .SHOULDER_RIDE_AVAILABLE, "true",
                                BondedCompanionPresentationAttributes
                                        .SHOULDER_RIDE_MOUNTED, "false"), null),
                false, bindingConfig(), "en-US");

        assertCommand(commands, "#Card #BondedShoulderRideButton.Visible", "true");
        assertCommand(commands, "#Card #BondedShoulderRideButton.Style", "Shoulder");
        assertCommand(commands, "#Card #BondedShoulderRideButton.Text", "");
        assertCommand(commands, "#Card #BondedShoulderRideButton.TooltipText",
                "Bring this companion to your shoulder");

        UICommandBuilder mounted = new UICommandBuilder();
        BondedCompanionCardPresenter.refreshDynamicState(mounted, "#Card", null,
                presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS,
                        true, Map.of(
                                BondedCompanionPresentationAttributes
                                        .SHOULDER_RIDE_AVAILABLE, "true",
                                BondedCompanionPresentationAttributes
                                        .SHOULDER_RIDE_MOUNTED, "true"), null),
                "en-US");
        assertCommand(mounted, "#Card #BondedShoulderRideButton.Text", "");
        assertCommand(mounted, "#Card #BondedShoulderRideButton.TooltipText",
                "Set this companion down");
    }

    private static void assertCommandSelector(
            UICommandBuilder commands, String selector
    ) {
        assertTrue(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)),
                () -> "Expected a command for " + selector);
    }

}
