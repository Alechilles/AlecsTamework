package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionNames;
import com.alechilles.alecstamework.companion.bonded.BondedRecords;
import com.alechilles.alecstamework.items.BondedCompanionActionFeedbackMapper;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Behavior of the bonded companion card: what each state says, shows and lets the player do. */
class BondedCompanionCardPresenterTest {
    private static final String LANGUAGE = "en-US";
    private static final String STATUS = "tamework.ui.linkedPanel.bonded.status.";
    private static final Map<String, String> FULL_FAMILY = Map.of(
            BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT, "1",
            BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT, "1",
            BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LABEL, "Full Dragons");

    @Test
    void activeTimedSessionShowsTheTimeLeftAGreenBarAndDismiss() {
        Bound card = bind(presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of("sessionDurationMs", "80000", "sessionRemainingMs", "40000",
                        "currentHealth", "320.0", "maxHealth", "400.0"), null));

        card.assertTone(BondedCompanionStateView.ACTIVE);
        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "sessionEndsIn", "0:40"), false);
        card.assertCommand("#BondedTimerFrame.Visible", "true");
        card.assertCommand("#BondedTimerFillSession.Visible", "true");
        card.assertCommand("#BondedTimerFillSession.Anchor", "134");
        card.assertCommand("#BondedTimerFillCooldown.Visible", "false");
        card.assertCommand("#BondedHealthText.Text", "320 / 400");
        card.assertPrimaryAction("dismiss:");
    }

    @Test
    void activeUntimedCompanionShowsNoTimer() {
        Bound card = bind(presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true, Map.of(), null));

        card.assertStatus(LocalizedText.resolve(LANGUAGE, STATUS + "active"), false);
        card.assertCommand("#BondedTimerFrame.Visible", "false");
        card.assertPrimaryAction("dismiss:");
    }

    /** Dismiss is greyed out when it is not possible, and the status line says why. */
    @Test
    void activeCompanionInAnotherWorldCannotBeDismissedAndSaysWhy() {
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                BondedCompanionStateView.ACTIVE, BondedCompanionStatusPresentation.Action.DISMISS,
                false, BondedCompanionActionBlockReason.WORLD_UNAVAILABLE, null, 0L), Map.of(), null));

        card.assertStatus(BondedCompanionActionFeedbackMapper.resolve(LANGUAGE,
                BondedCompanionActionBlockReason.WORLD_UNAVAILABLE), true);
        card.assertPrimaryActionDisabled();
    }

    @Test
    void storedCompanionThatCanBeSummonedSaysSoAndOffersSummon() {
        Bound card = bind(presentation(BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON, true, Map.of(), null));

        card.assertTone(BondedCompanionStateView.STORED);
        card.assertStatus(LocalizedText.resolve(LANGUAGE, STATUS + "readyToSummon"), false);
        card.assertCommand("#BondedTimerFrame.Visible", "false");
        card.assertCommand("#BondedPortraitDim.Visible", "false");
        card.assertPrimaryAction("summon:");
    }

    /** Summon stays in place, greyed out, until a summon is possible. */
    @Test
    void storedCooldownShowsTheWaitAsAWarningWithAnAmberBarAndNoSummon() {
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.STORED, BondedCompanionStatusPresentation.Action.SUMMON,
                        false, BondedCompanionActionBlockReason.COOLDOWN_ACTIVE, null, 40_000L),
                Map.of(BondedRecords.COOLDOWN_DURATION_MS, "80000"), null));

        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "summonIn", "0:40"), true);
        card.assertCommand("#BondedTimerFrame.Visible", "true");
        card.assertCommand("#BondedTimerFillCooldown.Visible", "true");
        card.assertCommand("#BondedTimerFillCooldown.Anchor", "134");
        card.assertCommand("#BondedTimerFillSession.Visible", "false");
        card.assertPrimaryActionDisabled();
    }

    @Test
    void storedCompanionOfAFullFamilyNamesTheActiveCompanionToDismiss() {
        java.util.HashMap<String, String> attributes = new java.util.HashMap<>(FULL_FAMILY);
        attributes.put(BondedRecords.ACTIVE_BLOCKER_NAME, "Ember");
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                BondedCompanionStateView.STORED, BondedCompanionStatusPresentation.Action.SUMMON,
                false, BondedCompanionActionBlockReason.CAPACITY_REACHED, null, 0L), attributes, null));

        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "dismissFirst", "Ember", 1, 1), true);
        card.assertPrimaryActionDisabled();
    }

    @Test
    void fullFamilyWithNoKnownActiveCompanionGivesTheCounts() {
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                BondedCompanionStateView.STORED, BondedCompanionStatusPresentation.Action.SUMMON,
                false, BondedCompanionActionBlockReason.CAPACITY_REACHED, null, 0L), FULL_FAMILY, null));

        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "familyFull", 1, 1), true);
        card.assertPrimaryActionDisabled();
    }

    @Test
    void storedCompanionBlockedForAnotherReasonShowsThatReason() {
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                BondedCompanionStateView.STORED, BondedCompanionStatusPresentation.Action.SUMMON,
                false, BondedCompanionActionBlockReason.PLACEMENT_UNAVAILABLE, null, 0L), Map.of(), null));

        card.assertStatus(BondedCompanionActionFeedbackMapper.resolve(LANGUAGE,
                BondedCompanionActionBlockReason.PLACEMENT_UNAVAILABLE), true);
        card.assertPrimaryActionDisabled();
    }

    @Test
    void deadCompanionThatCanBeRevivedShowsTheCostAndOffersRevive() {
        Bound card = bind(presentation(BondedCompanionStateView.DEAD,
                BondedCompanionStatusPresentation.Action.REVIVE, true,
                Map.of("currentHealth", "320", "maxHealth", "400"), quote(0L,
                        new BondedCompanionReviveQuote.CostLine("Ingredient_Life_Essence", 2, 2),
                        new BondedCompanionReviveQuote.CostLine("Ingredient_Amber", 4, 9))));

        card.assertTone(BondedCompanionStateView.DEAD);
        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "reviveCost.many", 6), false);
        card.assertCommand("#BondedStatusSecondary.Visible", "false");
        card.assertCommand("#BondedPortraitDim.Visible", "true");
        card.assertCommand("#BondedHealthText.Text", "0 / 400");
        card.assertCommand("#BondedHealthFill.Visible", "false");
        card.assertPrimaryAction("respawn:");
    }

    /** The cost page shows what is missing, so Revive opens it even when the player cannot pay. */
    @Test
    void deadCompanionWithMissingCostItemsKeepsReviveAndNamesTheShortfall() {
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.DEAD, BondedCompanionStatusPresentation.Action.REVIVE,
                        false, BondedCompanionActionBlockReason.PAYMENT_UNAVAILABLE, null, 0L), Map.of(),
                quote(0L, new BondedCompanionReviveQuote.CostLine("Ingredient_Life_Essence", 2, 1))));

        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "reviveCost.many", 2), false);
        card.assertCommand("#BondedStatusSecondary.Visible", "true");
        card.assertCommand("#BondedStatusSecondary.Text",
                LocalizedText.format(LANGUAGE, STATUS + "missingItems.one", 1));
        card.assertPrimaryAction("respawn:");
    }

    @Test
    void deadCompanionOnReviveCooldownShowsTheWaitWithAnAmberBarAndNoRevive() {
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.DEAD, BondedCompanionStatusPresentation.Action.REVIVE,
                        false, BondedCompanionActionBlockReason.COOLDOWN_ACTIVE, null, 0L),
                Map.of(BondedRecords.COOLDOWN_DURATION_MS, "544000"),
                quote(272L, new BondedCompanionReviveQuote.CostLine("Ingredient_Life_Essence", 2, 2))));

        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "reviveIn", "4:32"), false);
        card.assertCommand("#BondedTimerFillCooldown.Visible", "true");
        card.assertCommand("#BondedTimerFillCooldown.Anchor", "134");
        card.assertPrimaryActionDisabled();
    }

    /** A full family blocks a revive too; the cost page could not explain that. */
    @Test
    void deadCompanionOfAFullFamilyHasNoReviveAndSaysWhy() {
        Bound card = bind(presentation(new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.DEAD, BondedCompanionStatusPresentation.Action.REVIVE,
                        false, BondedCompanionActionBlockReason.PAYMENT_UNAVAILABLE, null, 0L), FULL_FAMILY,
                quote(0L, new BondedCompanionReviveQuote.CostLine("Ingredient_Life_Essence", 2, 1))));

        card.assertStatus(LocalizedText.format(LANGUAGE, STATUS + "familyFull", 1, 1), true);
        card.assertPrimaryActionDisabled();
    }

    @Test
    void timesUnderAnHourReadAsMinutesAndSecondsAndLongerTimesKeepTheSharedFormat() {
        assertEquals("0:05", BondedCompanionCardStatePresentation.clock(4_200L, LANGUAGE));
        assertEquals("59:59", BondedCompanionCardStatePresentation.clock(3_599_000L, LANGUAGE));
        assertEquals(LinkedNpcPanelStatusTextService.formatRemainingTime(5_400_000L, LANGUAGE),
                BondedCompanionCardStatePresentation.clock(5_400_000L, LANGUAGE));
    }

    /** A captured companion has no stored name or species; its role name key names it. */
    @Test
    void anUnnamedCompanionIsTitledByItsRoleNameInTheViewersLanguage() {
        // Any key the language files hold stands in for another mod's role name key.
        String nameKey = "tamework.ui.shared.item";
        for (String language : List.of("en-US", "de-DE")) {
            String roleName = LocalizedText.resolve(language, nameKey);
            BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                    "profile-7", "hydragon:dragons", "Tamed_RockDrakeT1", 4L,
                    null, null, "Female", null,
                    Map.of(BondedCompanionNames.NAME_KEY, nameKey), Map.of(),
                    new BondedCompanionStatusPresentation(
                            BondedCompanionStateView.STORED,
                            BondedCompanionStatusPresentation.Action.SUMMON,
                            true, null, 0L), null);
            UICommandBuilder commands = new UICommandBuilder();

            BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(),
                    "#Card", UUID.randomUUID(), row, false, bindingConfig(), language);

            assertFalse(roleName.equals(nameKey));
            assertCommand(commands, "#Card #BondedName.Text", roleName);
            // The title already is the role name, so the line under it does not repeat it.
            assertFalse(Arrays.stream(commands.getCommands()).anyMatch(command ->
                    "#Card #BondedSubtitle.Text".equals(command.selector) && command.data.contains(roleName)));
        }
    }

    @Test
    void aNamedCompanionShowsItsRoleUnderTheNameItsLevelOnThePortraitAndItsGenderAsAnIcon() {
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons", "NordicDrake", 4L,
                "Wyatt", "Nordic Drake", "Female", null,
                Map.of("level", "3", "levelingConfigId", "saved-leveling",
                        "talentConfigId", "saved-talents", "talentSpentPoints", "0"),
                Map.of(), new BondedCompanionStatusPresentation(
                BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON,
                true, null, 0L), null);

        Bound card = bind(row);

        card.assertCommand("#BondedName.Text", "Wyatt");
        card.assertCommand("#BondedSubtitle.Text", "Nordic Drake");
        card.assertCommand("#BondedLevelChip.Visible", "true");
        card.assertCommand("#BondedLevelText.Text", "3");
        card.assertCommand("#BondedGenderFemaleIcon.Visible", "true");
        card.assertCommand("#BondedGenderMaleIcon.Visible", "false");
    }

    @Test
    void talentsButtonShowsUnspentPointsAndOpensTheTalentsPage() {
        Bound spent = bind(presentation(BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON, true,
                Map.of("level", "1", "levelingConfigId", "saved-leveling",
                        "talentConfigId", "saved-talents", "talentSpentPoints", "0"), null));
        spent.assertCommand("#BondedTalentPointAction.Visible", "true");
        spent.assertCommand("#BondedTalentPointBadge.Visible", "false");
        spent.assertEvent("#BondedTalentPointButton", "talents:");

        Bound unspent = bind(presentation(BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON, true,
                Map.of("level", "4", "levelingConfigId", "missing-config",
                        "talentConfigId", "saved-talents", "talentSpentPoints", "1"), null));
        unspent.assertCommand("#BondedTalentPointBadge.Visible", "true");
        unspent.assertCommand("#BondedTalentPointCount.Text", "2");
    }

    @Test
    void flightToggleShowsOnlyForAnActiveFlyerAndReflectsItsMode() {
        for (boolean airborne : new boolean[] {false, true}) {
            Bound card = bind(presentation(BondedCompanionStateView.ACTIVE,
                    BondedCompanionStatusPresentation.Action.DISMISS, true, Map.of(
                            BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                            BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE,
                            Boolean.toString(airborne)), null));
            card.assertCommand("#BondedFlightToggleButton.Visible", "true");
            card.assertCommand("#BondedFlightToggleButton.Style", airborne ? "FlightAirborne" : "FlightGrounded");
            card.assertCommand("#BondedFlightToggleButton.TooltipText", LocalizedText.resolve(LANGUAGE,
                    airborne ? "tamework.ui.linkedPanel.bonded.flight.switchToGround"
                            : "tamework.ui.linkedPanel.bonded.flight.switchToFlight"));
            card.assertEvent("#BondedFlightToggleButton", card.uuid.toString());
        }
        for (BondedCompanionPanelPresentation row : List.of(
                presentation(BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON, true,
                        Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true"), null),
                presentation(BondedCompanionStateView.DEAD,
                        BondedCompanionStatusPresentation.Action.REVIVE, true,
                        Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true"), null),
                presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true, Map.of(), null))) {
            Bound card = bind(row);
            card.assertCommand("#BondedFlightToggleButton.Visible", "false");
            assertFalse(Arrays.stream(card.events.getEvents())
                    .anyMatch(event -> event.selector.endsWith("#BondedFlightToggleButton")));
        }
    }

    @Test
    void shoulderRideShowsForAnActiveSupportedCompanionAndFollowsItsMountState() {
        Bound card = bind(presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true, Map.of(
                        BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE, "true",
                        BondedCompanionPresentationAttributes.SHOULDER_RIDE_MOUNTED, "false"), null));
        card.assertCommand("#BondedShoulderRideButton.Visible", "true");
        card.assertCommand("#BondedShoulderRideButton.Style", "ShoulderOff");
        card.assertCommand("#BondedShoulderRideButton.TooltipText", LocalizedText.resolve(LANGUAGE,
                "tamework.ui.linkedPanel.bonded.shoulder.toMe.tooltip"));

        UICommandBuilder mounted = new UICommandBuilder();
        BondedCompanionCardPresenter.refreshDynamicState(mounted, "#Card", null,
                presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true, Map.of(
                                BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE, "true",
                                BondedCompanionPresentationAttributes.SHOULDER_RIDE_MOUNTED, "true"), null),
                false, LANGUAGE);
        assertCommand(mounted, "#Card #BondedShoulderRideButton.Style", "ShoulderOn");
        assertCommand(mounted, "#Card #BondedShoulderRideButton.TooltipText", LocalizedText.resolve(LANGUAGE,
                "tamework.ui.linkedPanel.bonded.shoulder.down.tooltip"));

        bind(presentation(BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON, true, Map.of(
                        BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE, "true"), null))
                .assertCommand("#BondedShoulderRideButton.Visible", "false");
    }

    /** Hidden buttons leave no gap: the talents button sits next to the nearest visible button. */
    @Test
    void iconRowClosesUpWhenFlightAndShoulderRideAreAbsent() {
        Bound plain = bind(presentation(BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON, true, Map.of(), null));
        plain.assertCommand("#BondedUnlinkButton.Anchor", "822");
        plain.assertCommand("#BondedTalentPointAction.Anchor", "790");

        Bound full = bind(presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true, Map.of(
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE, "true"), null));
        full.assertCommand("#BondedShoulderRideButton.Anchor", "790");
        full.assertCommand("#BondedFlightToggleButton.Anchor", "758");
        full.assertCommand("#BondedTalentPointAction.Anchor", "726");
    }

    @Test
    void pendingAbandonReplacesTheActionsWithConfirmAndCancel() {
        UUID cardUuid = UUID.randomUUID();
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        BondedCompanionCardPresenter.bind(commands, events, "#Card", cardUuid,
                presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true, Map.of(
                                BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true"), null),
                true, bindingConfig(), LANGUAGE);

        assertCommand(commands, "#Card #BondedUnlinkButton.Visible", "false");
        assertCommand(commands, "#Card #BondedUnlinkConfirmButton.Visible", "true");
        assertCommand(commands, "#Card #BondedUnlinkConfirmText.Visible", "true");
        assertCommand(commands, "#Card #BondedUnlinkCancelButton.Visible", "true");
        assertCommand(commands, "#Card #BondedPrimaryAction.Visible", "false");
        assertCommand(commands, "#Card #BondedTalentPointAction.Visible", "false");
        assertCommand(commands, "#Card #BondedFlightToggleButton.Visible", "false");
        assertTrue(Arrays.stream(events.getEvents()).anyMatch(event ->
                "#Card #BondedUnlinkConfirmButton".equals(event.selector)
                        && event.data.contains("unlink:" + cardUuid)));
        assertFalse(Arrays.stream(events.getEvents())
                .anyMatch(event -> event.data.contains("dismiss:" + cardUuid)));
    }

    @Test
    void dynamicRefreshPatchesTheLiveHealthBarWithoutRecreatingTheCard() {
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.refreshDynamicState(commands, "#Card", null,
                presentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true,
                        Map.of("currentHealth", "125", "maxHealth", "250"), null), false, LANGUAGE);

        assertCommand(commands, "#Card #BondedHealthText.Text", "125 / 250");
        assertCommand(commands, "#Card #BondedHealthFill.Anchor", "139");
    }

    /** A provisioned or granted companion has no stats until its first summon. */
    @Test
    void aNeverSummonedCompanionShowsAFullHealthBarWithoutNumbers() {
        Bound stored = bind(presentation(BondedCompanionStateView.STORED,
                BondedCompanionStatusPresentation.Action.SUMMON, true, Map.of(), null));

        stored.assertCommand("#BondedHealthFill.Visible", "true");
        stored.assertCommand("#BondedHealthFill.Anchor", "278");
        assertFalse(Arrays.stream(stored.commands.getCommands()).anyMatch(command ->
                "#Card #BondedHealthText.Text".equals(command.selector) && command.data.contains("/")));

        Bound dead = bind(presentation(BondedCompanionStateView.DEAD,
                BondedCompanionStatusPresentation.Action.REVIVE, false, Map.of(), null));

        dead.assertCommand("#BondedHealthFill.Visible", "false");
        dead.assertCommand("#BondedHealthText.Text", "0 / 100");
    }

    /** The per-tick path: a running timer patches the sentence and bar and binds no input again. */
    @Test
    void liveRefreshUpdatesTheTimerAndHealthWithoutRebindingInput() {
        UUID cardUuid = UUID.randomUUID();
        BondedCompanionPanelPresentation before = presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        "sessionDurationMs", "80000", "sessionRemainingMs", "41000",
                        "currentHealth", "100", "maxHealth", "100"), null);
        BondedCompanionPanelPresentation after = presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        "sessionDurationMs", "80000", "sessionRemainingMs", "40000",
                        "currentHealth", "90", "maxHealth", "100"), null);
        assertTrue(BondedCompanionCardDynamicState.changedOnlyByLiveFields(before, after));
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        LinkedNpcPanelCardDynamicPresenter.refresh(commands, events, "#Card", cardUuid,
                null, null, CommandPanelFeaturePresentation.bonded(before),
                CommandPanelFeaturePresentation.bonded(after), false, bindingConfig(), LANGUAGE);

        assertCommand(commands, "#Card #BondedHealthText.Text", "90 / 100");
        assertCommand(commands, "#Card #BondedStatusText.Text",
                LocalizedText.format(LANGUAGE, STATUS + "sessionEndsIn", "0:40"));
        assertEquals(0, events.getEvents().length, "Live patches must preserve existing input handlers.");
        assertFalse(Arrays.stream(commands.getCommands()).anyMatch(command ->
                        command.selector.contains("#BondedFlight")),
                "Timer and health updates must leave the flight control untouched.");
    }

    @Test
    void liveRefreshUpdatesFlightFeedbackWhenTheModeChanges() {
        BondedCompanionPanelPresentation grounded = presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true"), null);
        BondedCompanionPanelPresentation airborne = presentation(BondedCompanionStateView.ACTIVE,
                BondedCompanionStatusPresentation.Action.DISMISS, true,
                Map.of(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE, "true",
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE, "true"), null);
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionCardPresenter.refreshDynamicState(commands, "#Card", grounded, airborne, false, LANGUAGE);

        assertCommand(commands, "#Card #BondedFlightToggleButton.Style", "FlightAirborne");
    }

    private static BondedCompanionReviveQuote quote(long cooldownSeconds,
                                                    BondedCompanionReviveQuote.CostLine... costs) {
        return new BondedCompanionReviveQuote("profile-7", true, List.of(costs), cooldownSeconds, 4L);
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

    private static Bound bind(BondedCompanionPanelPresentation row) {
        Bound bound = new Bound();
        BondedCompanionCardPresenter.bind(bound.commands, bound.events, "#Card", bound.uuid,
                row, false, bindingConfig(), LANGUAGE);
        return bound;
    }

    private static void assertCommand(UICommandBuilder commands, String selector, String expected) {
        assertTrue(Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)
                                && command.data.contains(expected)),
                () -> "Expected " + selector + " to contain " + expected
                        + "; actual data: " + Arrays.stream(commands.getCommands())
                        .filter(command -> selector.equals(command.selector))
                        .map(command -> command.data)
                        .toList());
    }

    /** One bound card and the checks the status cases share. */
    private static final class Bound {
        private final UICommandBuilder commands = new UICommandBuilder();
        private final UIEventBuilder events = new UIEventBuilder();
        private final UUID uuid = UUID.randomUUID();

        void assertCommand(String selector, String expected) {
            BondedCompanionCardPresenterTest.assertCommand(commands, "#Card " + selector, expected);
        }

        /** Exactly the tone (row plate, portrait frame and pill) of the given state is visible. */
        void assertTone(BondedCompanionStateView expected) {
            for (BondedCompanionStateView candidate : BondedCompanionStateView.values()) {
                assertCommand(BondedCompanionCardPresenter.toneSelector(candidate) + ".Visible",
                        Boolean.toString(candidate == expected));
            }
        }

        /** The sentence is bound to the normal or the warning line, and only that line shows. */
        void assertStatus(String sentence, boolean warning) {
            assertFalse(sentence.isBlank());
            assertCommand(warning ? "#BondedStatusWarning.Text" : "#BondedStatusText.Text", sentence);
            assertCommand("#BondedStatusWarning.Visible", Boolean.toString(warning));
            assertCommand("#BondedStatusText.Visible", Boolean.toString(!warning));
        }

        void assertPrimaryAction(String commandPrefix) {
            assertCommand("#BondedPrimaryAction.Visible", "true");
            assertCommand("#BondedPrimaryAction.Disabled", "false");
            assertEvent("#BondedPrimaryAction", commandPrefix + uuid);
        }

        /** The button stays in place, greyed out and unbound, when its action is not possible. */
        void assertPrimaryActionDisabled() {
            assertCommand("#BondedPrimaryAction.Visible", "true");
            assertCommand("#BondedPrimaryAction.Disabled", "true");
            assertFalse(Arrays.stream(events.getEvents())
                            .anyMatch(event -> event.selector.endsWith("#BondedPrimaryAction")),
                    "An action that is not possible must not be bound.");
        }

        void assertEvent(String selector, String data) {
            assertTrue(Arrays.stream(events.getEvents()).anyMatch(event ->
                            ("#Card " + selector).equals(event.selector) && event.data.contains(data)),
                    () -> "Expected an event on " + selector + " carrying " + data);
        }
    }
}
