package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
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
                        BondedCompanionState.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON,
                        true, null, 0L), null);
        UICommandBuilder commands = new UICommandBuilder();

        assertDoesNotThrow(() -> LinkedNpcPanelFeatureBinder.bind(
                commands, new UIEventBuilder(), "#Card", UUID.randomUUID(),
                CommandPanelFeaturePresentation.bonded(row), bindingConfig(), "en-US"));
        assertFalse(java.util.Arrays.stream(commands.getCommands())
                .anyMatch(command -> command.selector.endsWith(".Enabled")));
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
    void durableDetailsIncludeSpeciesRoleAttributesAndMiniwyvernExtension() {
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons",
                "Bonded_Miniwyvern_Storm", 4L, "Nimbus",
                "Miniwyvern", "Male", "Storm Miniwyvern",
                Map.of("level", "7", "healthPercent", "63.25"),
                Map.of("hydragon:bond",
                        "{\"archetype\":\"storm\",\"ability\":\"dash\"}"),
                new BondedCompanionStatusPresentation(
                        BondedCompanionState.STORED,
                        BondedCompanionStatusPresentation.Action.SUMMON,
                        true, null, 0L), null);

        String detail = LinkedNpcPanelFeatureBinder.bondedDetailText(row);

        assertTrue(detail.contains("Miniwyvern"));
        assertTrue(detail.contains("Storm Miniwyvern"));
        assertTrue(detail.contains("level: 7"));
        assertTrue(detail.contains("archetype"));
        assertFalse(detail.contains("Bonded_Miniwyvern_Storm"));
    }

    @Test
    void deadBondedCardUsesPaidReviveWithoutLegacyLinkFallback() {
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "profile-7", "hydragon:dragons",
                "Bonded_Miniwyvern_Storm", 8L, "Nimbus",
                "Miniwyvern", "Male", "Storm Miniwyvern", Map.of(), Map.of(),
                new BondedCompanionStatusPresentation(
                        BondedCompanionState.DEAD,
                        BondedCompanionStatusPresentation.Action.REVIVE,
                        true, null, 0L),
                new BondedCompanionReviveQuote(
                        "profile-7", true, "Ingredient_Life_Essence", 2,
                        true, 0L, 9L));
        CommandPanelFeaturePresentation feature =
                CommandPanelFeaturePresentation.bonded(row);

        assertTrue(feature.managesPaidRevival());
        assertTrue(LinkedNpcPanelFeatureBinder.paidReviveVisible(feature));
        assertTrue(feature.managesRosterRow());
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
