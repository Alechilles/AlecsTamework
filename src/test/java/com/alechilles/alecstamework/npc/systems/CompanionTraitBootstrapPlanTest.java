package com.alechilles.alecstamework.npc.systems;

import static com.alechilles.alecstamework.npc.systems.CompanionTraitBootstrapPlan.FULL_REPAIR;
import static com.alechilles.alecstamework.npc.systems.CompanionTraitBootstrapPlan.LIFE_STAGE_ONLY;
import static com.alechilles.alecstamework.npc.systems.CompanionTraitBootstrapPlan.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import org.junit.jupiter.api.Test;

/** Protects valid legacy NPC traits from an unnecessary full bootstrap. */
class CompanionTraitBootstrapPlanTest {
    private static final String CONFIG_ID = "Traits_Default";

    @Test
    void classifiesTheSmallestRequiredLoadRepair() {
        TameworkTraitsComponent validTraits = traits(CONFIG_ID, 42L);

        assertEquals(NONE, CompanionTraitBootstrapPlan.classify(validTraits, CONFIG_ID, true));
        assertEquals(LIFE_STAGE_ONLY, CompanionTraitBootstrapPlan.classify(validTraits, CONFIG_ID, false));
        assertEquals(FULL_REPAIR, CompanionTraitBootstrapPlan.classify(null, CONFIG_ID, false));
        assertEquals(FULL_REPAIR, CompanionTraitBootstrapPlan.classify(traits(CONFIG_ID, 0L), CONFIG_ID, true));
        assertEquals(FULL_REPAIR, CompanionTraitBootstrapPlan.classify(validTraits, "Traits_Updated", true));
    }

    private static TameworkTraitsComponent traits(String configId, long rollSeed) {
        return new TameworkTraitsComponent(
                configId,
                rollSeed,
                new TameworkTraitsComponent.TraitValue[] {
                        new TameworkTraitsComponent.TraitValue("SizeMultiplier", 1.0)
                }
        );
    }
}
