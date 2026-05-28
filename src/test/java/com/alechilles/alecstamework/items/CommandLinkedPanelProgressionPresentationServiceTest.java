package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierBreakdownService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLinkedPanelProgressionPresentationServiceTest {
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
