package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Renders read-only, detached examples with the production companion-card documents.
 *
 * <p>This class deliberately does not accept a {@code UIEventBuilder}. The cards keep
 * their production layout, hover, and tooltip controls, but no card control can send an
 * action to the command-panel host.</p>
 */
final class TameworkCompanionGuideCardRenderer {
    static final int EXAMPLE_COUNT = 3;

    private static final String EXAMPLE_VIEWPORT = "#TameworkCompanionGuideExampleCard";
    private static final String GENERIC_CARD_UI = "TameworkLinkedNpcPanelCard.ui";
    private static final String BONDED_CARD_UI = "TameworkBondedCompanionPanelCard.ui";
    private static final String PORTRAIT_OVERLAY_UI = "TameworkCompanionGuideCardPortrait.ui";
    private static final LinkedNpcPanelCardBinder.CardBindingConfig GUIDE_CARD_CONFIG =
            new LinkedNpcPanelCardBinder.CardBindingConfig(
                    GENERIC_CARD_UI, "Guide", "guide:", "guide:", "guide:",
                    "guide:", "guide:", "guide:", "guide:", "guide:",
                    "guide:", "guide:", "guide:", "guide:", "guide:",
                    "guide:", "guide:", true, false);

    private TameworkCompanionGuideCardRenderer() {
    }

    static boolean hasExampleScenarios(int topicIndex) {
        return topicIndex == 2 || topicIndex == 3 || topicIndex == 6 || topicIndex == 7 || topicIndex == 8;
    }

    static void render(UICommandBuilder commands,
                       TameworkCompanionGuideContent.Topic topic,
                       int topicIndex,
                       int exampleIndex,
                       String language) {
        commands.clear(EXAMPLE_VIEWPORT);
        if (topicIndex == 8) {
            renderBonded(commands, topic, exampleIndex, language);
            return;
        }
        renderGeneric(commands, topic, topicIndex, exampleIndex, language);
    }

    private static void renderGeneric(UICommandBuilder commands,
                                      TameworkCompanionGuideContent.Topic topic,
                                      int topicIndex,
                                      int exampleIndex,
                                      String language) {
        commands.append(EXAMPLE_VIEWPORT, GENERIC_CARD_UI);
        commands.append(EXAMPLE_VIEWPORT, PORTRAIT_OVERLAY_UI);
        String card = EXAMPLE_VIEWPORT + "[0]";
        LinkedNpcEntry entry = genericEntry(topicIndex, exampleIndex, language);
        // The full card binder is used with an event builder that is never emitted.
        // Its controls retain normal hover and tooltip behavior but cannot reach the host.
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), card, entry,
                false, GUIDE_CARD_CONFIG, language);
        if (topicIndex == 2) {
            String group = card + " #GroupSelector";
            commands.set(group + ".Visible", true);
            commands.set(group + "Marker.Visible", true);
            commands.set(group + "Label.Visible", true);
            commands.set(group + ".Entries", List.of(new DropdownEntryInfo(
                    LocalizableString.fromString(entry.groupName()), entry.groupId())));
            commands.set(group + ".MaxSelection", 0);
            commands.set(group + ".SelectedValues", List.of(LocalizableString.fromString(entry.groupId())));
            commands.set(group + "Label.Text", entry.groupName());
            LinkedNpcPanelGroupTabBinder.bind(commands, group, entry);
        }
        commands.set(EXAMPLE_VIEWPORT + " #TameworkCompanionGuideGenericPortrait.Visible", true);
        commands.set(EXAMPLE_VIEWPORT + " #TameworkCompanionGuideBondedPortrait.Visible", false);
    }

    private static LinkedNpcEntry genericEntry(int topicIndex, int exampleIndex,
                                                String language) {
        boolean captured = (topicIndex == 6 || topicIndex == 7) && exampleIndex == 1;
        boolean dead = topicIndex == 7 && exampleIndex == 2;
        boolean unloaded = captured || topicIndex == 7 && exampleIndex == 0 || dead;
        boolean active = !(topicIndex == 2 && exampleIndex == 2);
        String nameKey = topicIndex == 2 && exampleIndex > 0
                ? exampleIndex == 1 ? "sample.nameBramble" : "sample.nameWillow" : "sample.name";
        String groupId = topicIndex == 2 ? exampleIndex == 2 ? "barn" : "meadow" : null;
        String groupName = groupId == null ? null : guideText(language, "sample.group." + groupId);
        int hunger = topicIndex == 3 && exampleIndex == 0 ? 20 : 85;
        int thirst = topicIndex == 3 && exampleIndex == 0 ? 30 : 90;
        int happiness = topicIndex == 3 && exampleIndex == 2 ? 75 : 68;
        LinkedNpcEntry.FutureStat level = topicIndex == 5 || topicIndex == 1
                ? new LinkedNpcEntry.FutureStat("6", 45, 100,
                guideText(language, "sample.traitsTalents.state"), "") : null;
        LinkedNpcEntry.FutureStat points = topicIndex == 5
                ? new LinkedNpcEntry.FutureStat("", 1, 1) : null;
        LinkedNpcTraitIndicator[] traits = topicIndex == 5 || topicIndex == 1
                ? new LinkedNpcTraitIndicator[] {
                        new LinkedNpcTraitIndicator("", "Tamework/LinkedPanelIcons/Trait_Disposition.png",
                                LocalizedText.resolve(language, "tamework.traits.disposition.name"),
                                guideText(language, "sample.trait.attitude"), .70, false, false),
                        new LinkedNpcTraitIndicator("", "Tamework/LinkedPanelIcons/Trait_Fertility.png",
                                LocalizedText.resolve(language, "tamework.traits.fertility.name"),
                                guideText(language, "sample.trait.fertility"), .42, true, false)
                } : LinkedNpcTraitIndicator.EMPTY;
        LinkedNpcEntry entry = new LinkedNpcEntry(
                UUID.nameUUIDFromBytes(("guide-" + topicIndex + "-" + exampleIndex).getBytes()),
                guideText(language, nameKey), "Female",
                78, 100, happiness, 100, happiness, "", hunger, 100, thirst, 100,
                !unloaded, topicIndex == 9, dead, captured, false, false, 0L, null,
                level, points, traits, false, false, topicIndex == 5, topicIndex == 5,
                !captured, active, null, null, groupId, groupName, exampleIndex == 2 ? "#d5b15c" : "#85b99a",
                topicIndex == 4 || topicIndex == 3, topicIndex == 4 || topicIndex == 3,
                false, 0L, 0.0, topicIndex == 4 || topicIndex == 3,
                false, 0L, 0.0, false, false, 0L
        ).withRoleSubtitle(guideText(language, "sample.species"))
                .withBreedingHappinessRatio(topicIndex == 4 || topicIndex == 3 ? .60 : -1.0)
                .withFlightToggle(topicIndex == 9, exampleIndex == 1)
                .withShoulderRide(topicIndex == 9, exampleIndex == 2);
        return captured ? entry : entry.withOwnedActions();
    }

    private static void renderBonded(UICommandBuilder commands,
                                     TameworkCompanionGuideContent.Topic topic,
                                     int exampleIndex,
                                     String language) {
        commands.append(EXAMPLE_VIEWPORT, BONDED_CARD_UI);
        commands.append(EXAMPLE_VIEWPORT, PORTRAIT_OVERLAY_UI);
        String card = EXAMPLE_VIEWPORT + "[0]";
        boolean active = exampleIndex == 0;
        boolean dead = exampleIndex == 2;
        BondedCompanionStateView state = dead ? BondedCompanionStateView.DEAD
                : active ? BondedCompanionStateView.ACTIVE : BondedCompanionStateView.STORED;
        BondedCompanionStatusPresentation.Action action = dead
                ? BondedCompanionStatusPresentation.Action.REVIVE
                : active ? BondedCompanionStatusPresentation.Action.DISMISS
                : BondedCompanionStatusPresentation.Action.SUMMON;
        BondedCompanionPanelPresentation row = new BondedCompanionPanelPresentation(
                "guide-profile", "guide-roster", "guide-wolf", 0L,
                guideText(language, "sample.name"), guideText(language, "sample.species"),
                "Female", null,
                Map.of("currentHealth", "78", "maxHealth", "100"), Map.of(),
                new BondedCompanionStatusPresentation(state, action, true, null, 0L), null
        );
        BondedCompanionCardPresenter.bind(commands, new UIEventBuilder(), card,
                UUID.nameUUIDFromBytes("guide-bonded".getBytes()), row, false,
                GUIDE_CARD_CONFIG, language);
        commands.set(EXAMPLE_VIEWPORT + " #TameworkCompanionGuideGenericPortrait.Visible", false);
        commands.set(EXAMPLE_VIEWPORT + " #TameworkCompanionGuideBondedPortrait.Visible", true);
    }

    private static String guideText(String language, String suffix) {
        return LocalizedText.resolve(language, "tamework.ui.guide." + suffix);
    }
}
