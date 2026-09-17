package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierBreakdownService;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLinkedPanelProgressionPresentationServiceTest {
    @Test
    void lowerPreferredAppetiteUsesBeneficialColorAndCorrectDescription() {
        var config = com.alechilles.alecstamework.config.assets.TwTraitConfig.CODEC.decode(
                org.bson.BsonDocument.parse("""
                    {"Traits":[{"Id":"Appetite","DisplayName":"Appetite",
                    "EffectKey":"NeedsHungerDecayMultiplier","MutationPreference":"LOWER",
                    "BreedingMin":0.75,"BreedingMax":1.25,"Default":1.0}]}
                    """), new com.hypixel.hytale.codec.ExtraInfo());
        var service = new CommandLinkedPanelProgressionPresentationService();
        var beneficial = service.buildSavedTraitIndicators(config, java.util.Map.of("appetite", 0.95), null, "en-US")[0];
        assertEquals(false, beneficial.belowDefault());
        assertEquals("Appetite\nDecreases how quickly hunger falls by 5%.", beneficial.tooltipText());
        var harmful = service.buildSavedTraitIndicators(config, java.util.Map.of("appetite", 1.05), null, "en-US")[0];
        assertEquals(true, harmful.belowDefault());
        assertEquals("Appetite\nIncreases how quickly hunger falls by 5%.", harmful.tooltipText());
    }

    @Test
    void flatHappinessTooltipUsesPointsInsteadOfLegacyMultipliers() {
        String tooltip = CommandLinkedPanelProgressionPresentationService.buildModifierTooltip(
                List.of(
                        new CompanionProgressionModifierBreakdownService.ModifierBreakdown(
                                "HappinessGainMultiplier", 1.3, 1.0, 1.0, 1.3),
                        new CompanionProgressionModifierBreakdownService.ModifierBreakdown(
                                "HappinessFlatBonus", 4.0, 1.0, 4.0, 1.0)),
                0.0, 0.0, "en-US", 8.2, 3.1);

        assertEquals("Modifiers: Total - [Level - Talents - Traits]\n"
                + "Happiness: +11 - [+0 / +3 / +8]", tooltip);
    }

    @Test
    void levelFutureStatCarriesCompactLevelAndXpTooltipHeader() {
        CommandLinkedPanelProgressionPresentationService service =
                new CommandLinkedPanelProgressionPresentationService();
        CompanionLevelingService.LevelingSnapshot snapshot = new CompanionLevelingService.LevelingSnapshot(
                "TwLevelingConfig_Test",
                12,
                147.0,
                1000.0,
                853.0,
                1153.0,
                30,
                false
        );

        LinkedNpcEntry.FutureStat stat = service.buildLevelFutureStat(snapshot, "en-US", "Modifiers");

        assertEquals("Level: 12", stat.label());
        assertEquals(147, stat.current());
        assertEquals(300, stat.max());
        assertEquals("Level: 12/30 - 147/300 XP", stat.tooltipHeaderText());
        assertEquals("Modifiers", stat.tooltipText());
    }

    @Test
    void formatsModifierTooltipWithSourceBreakdownAndAbsoluteBonuses() {
        String tooltip = CommandLinkedPanelProgressionPresentationService.buildModifierTooltip(
                List.of(
                        new CompanionProgressionModifierBreakdownService.ModifierBreakdown(
                                "MaxHealthMultiplier",
                                1.10 * 1.02 * 1.01,
                                1.10,
                                1.02,
                                1.01
                        ),
                        new CompanionProgressionModifierBreakdownService.ModifierBreakdown(
                                "MoveSpeedMultiplier",
                                1.05,
                                1.03,
                                1.02,
                                1.00
                        )
                ),
                100.0,
                6.0
        );

        assertEquals(
                String.join(
                        "\n",
                        "Modifiers: Total - [Level - Talents - Traits]",
                        "Health: +13% (13 HP) - [+10% / +2% / +1%]",
                        "Speed: +5% (0.3 m/s) - [+3% / +2% / +0%]"
                ),
                tooltip
        );
    }

    @Test
    void omitsAbsoluteBonusWhenBaselineIsUnavailable() {
        String tooltip = CommandLinkedPanelProgressionPresentationService.buildModifierTooltip(
                List.of(new CompanionProgressionModifierBreakdownService.ModifierBreakdown(
                        "DamageTakenMultiplier",
                        0.92,
                        1.00,
                        0.92,
                        1.00
                )),
                0.0,
                0.0
        );

        assertEquals(
                String.join(
                        "\n",
                        "Modifiers: Total - [Level - Talents - Traits]",
                        "Damage Taken: -8% - [+0% / -8% / +0%]"
                ),
                tooltip
        );
    }
}
