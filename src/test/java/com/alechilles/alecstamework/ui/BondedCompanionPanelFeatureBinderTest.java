package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import org.junit.jupiter.api.Test;

/** Pure presentation regressions for durable bonded cards. */
class BondedCompanionPanelFeatureBinderTest {
    @Test
    void bondedBindingDoesNotReadGenericRosterOrWriteEnabledProperties() {
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons",
                "Bonded_Miniwyvern_Storm", 4L, "Nimbus",
                "Miniwyvern", "Male", "Storm Miniwyvern", Map.of(), Map.of(),
                new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON,
                        true, null, 0L), null);
        UICommandBuilder commands = new UICommandBuilder();

        assertDoesNotThrow(() -> LinkedNpcPanelFeatureBinder.bind(
                commands, new UIEventBuilder(), "#Card", UUID.randomUUID(),
                CommandPanelFeaturePresentation.bonded(row), bindingConfig(), "en-US"));
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                .anyMatch(command -> command.selector.endsWith(".Enabled")));
    }

    /** The state caption is text for the viewer, so it follows the viewer's language. */
    @Test
    void bondedStateCaptionIsShownInTheViewersLanguage() {
        BondedCompanionStatusPresentation stored = new BondedCompanionStatusPresentation(
                BondedCompanionStateView.STORED, BondedCompanionStatusPresentation.Action.SUMMON, true, null, 0L);
        BondedCompanionStatusPresentation dead = new BondedCompanionStatusPresentation(
                BondedCompanionStateView.DEAD, BondedCompanionStatusPresentation.Action.REVIVE, true, null, 0L);

        String english = LinkedNpcPanelFeatureBinder.bondedStateText(stored, "en-US");
        assertFalse(english.isBlank());
        assertNotEquals(english, LinkedNpcPanelFeatureBinder.bondedStateText(stored, "de-DE"));
        assertNotEquals(english, LinkedNpcPanelFeatureBinder.bondedStateText(dead, "en-US"));
    }

    @Test
    void ownerFamilyRowsSuppressLegacyActionsWithoutFeaturePresentation() {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcEntry entry = new LinkedNpcEntry(
                UUID.randomUUID(), "Dragon", 100, 100, 100, 100, "",
                100, 100, 100, 100, true, false, false, false, false,
                false, 0L, new LinkedNpcTraitIndicator[0]);

        LinkedNpcPanelCardBinder.bind(
                commands, new UIEventBuilder(), 0, entry, false, false,
                bindingConfig(true), "en-US", null);

        java.util.List<String> visibilityCommands = java.util.Arrays.stream(commands.getCommands())
                .filter(command -> command.selector.endsWith("#LinkButton.Visible")
                        || command.selector.endsWith("#RemoveButton.Visible"))
                .map(command -> command.data)
                .toList();
        assertTrue(visibilityCommands.size() == 2
                && visibilityCommands.stream().allMatch(data -> data.contains("false")),
                visibilityCommands.toString());
    }

    @Test
    void detailLineShowsSpeciesRoleLevelAndHealthAndNoInternalKeys() {
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons",
                "Bonded_Miniwyvern_Storm", 4L, "Nimbus",
                "Miniwyvern", "Male", "Storm Miniwyvern",
                Map.of("level", "7", "currentHealth", "63.4", "maxHealth", "100.0",
                        "levelingConfigId", "hydragon:leveling", "bonded.activeCapacity.count", "1"),
                Map.of("hydragon:bond",
                        "{\"archetype\":\"storm\",\"ability\":\"dash\"}"),
                new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON,
                        true, null, 0L), null);

        String detail = LinkedNpcPanelFeatureBinder.bondedDetailText(row, "en-US");

        assertTrue(detail.contains("Miniwyvern"));
        assertTrue(detail.contains("Storm Miniwyvern"));
        assertTrue(detail.contains("7"), detail);
        assertTrue(detail.contains("63") && detail.contains("100"), detail);
        // Raw attribute keys, config ids, role ids and other mods' extension data stay off the card.
        assertFalse(detail.contains("level:"), detail);
        assertFalse(detail.contains("hydragon:leveling"), detail);
        assertFalse(detail.contains("activeCapacity"), detail);
        assertFalse(detail.contains("archetype"), detail);
        assertFalse(detail.contains("Bonded_Miniwyvern_Storm"));
    }

    @Test
    void deadBondedCardUsesPaidReviveWithoutLegacyLinkFallback() {
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons",
                "Bonded_Miniwyvern_Storm", 8L, "Nimbus",
                "Miniwyvern", "Male", "Storm Miniwyvern", Map.of(), Map.of(),
                new BondedCompanionStatusPresentation(
                        BondedCompanionStateView.DEAD,
                        BondedCompanionStatusPresentation.Action.REVIVE,
                        true, null, 0L),
                new BondedCompanionReviveQuote(
                        "profile-7", true, List.of(
                        new BondedCompanionReviveQuote.CostLine(
                                "Ingredient_Life_Essence", 2, 2)),
                        0L, 9L));
        CommandPanelFeaturePresentation feature =
                CommandPanelFeaturePresentation.bonded(row);

        assertTrue(feature.managesPaidRevival());
        assertTrue(LinkedNpcPanelFeatureBinder.paidReviveVisible(feature));
        assertTrue(feature.managesRosterRow());
    }

    /** A generic roster card hides Summon until it can be used, and binds it only then. */
    @Test
    void genericRosterSummonButtonShowsOnlyWhileSummonIsPossible() {
        UUID npcUuid = UUID.randomUUID();
        for (boolean cooling : new boolean[] {true, false}) {
            CommandRosterStatusPresentation stored = new CommandRosterStatusPresentation(
                    "profile", "test:horn", CommandTimedSummoningState.ROSTER_STORED, 1L, null,
                    60_000L, false, cooling ? 30_000L : 0L, 0, 0, null, null);
            UICommandBuilder commands = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();

            LinkedNpcPanelFeatureBinder.bind(commands, events, "#Card", npcUuid,
                    new CommandPanelFeaturePresentation(stored, null), bindingConfig(), "en-US");

            String expected = Boolean.toString(!cooling);
            assertTrue(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                            "#Card #RosterSummonButton.Visible".equals(command.selector)
                                    && command.data.contains(expected)),
                    "cooling=" + cooling);
            assertTrue(java.util.Arrays.stream(events.getEvents()).anyMatch(event ->
                    event.data.contains("summon:" + npcUuid)) == !cooling, "cooling=" + cooling);
        }
    }

    private static LinkedNpcPanelCardBinder.CardBindingConfig bindingConfig() {
        return bindingConfig(false);
    }

    private static LinkedNpcPanelCardBinder.CardBindingConfig bindingConfig(
            boolean ownerCommandFamilyRoster) {
        return new LinkedNpcPanelCardBinder.CardBindingConfig(
                "card.ui", "Command", "link:", "unlink:", "group:",
                "active:", "breed:", "release:", "cull:", "respawn:",
                "summon:", "dismiss:", "locate:", "recall:", "home:",
                "return:", "talents:", true, ownerCommandFamilyRoster);
    }
}
