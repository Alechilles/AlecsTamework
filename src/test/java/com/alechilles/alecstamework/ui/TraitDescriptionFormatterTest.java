package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraitDescriptionFormatterTest {
    @Test
    void damageDescriptionsUseActualEffectInsteadOfBreedingRangePosition() {
        TwTraitConfig.TraitDefinition strength = definition("DamageDealtMultiplier", null);
        assertEquals("Strength\nIncreases attack damage by 20%.", describe(strength, 1.2));
        assertEquals("Strength\nDecreases attack damage by 20%.", describe(strength, 0.8));
        assertEquals("Strength\nChanges attack damage by 0%.", describe(strength, 1.0));
    }

    @Test
    void toughnessDescribesInverseDamageReduction() {
        TwTraitConfig.TraitDefinition toughness = definition("DamageTakenMultiplier", null);
        assertEquals("Strength\nDecreases damage received by 16.67%.", describe(toughness, 1.2));
        assertEquals("Strength\nIncreases damage received by 25%.", describe(toughness, 0.8));
    }

    @Test
    void configuredDescriptionDecodesAndResolvesNamedPlaceholdersForRecipient() {
        TwTraitConfig.TraitDefinition custom = definition("CustomEffect",
                "{direction}: {percent}% ({signedPercent}%, {value})");
        assertEquals("Trait\nDecreases: 12.5% (-12.5%, 0.88)",
                TraitDescriptionFormatter.append("Trait", custom, 0.875, null, "en-US"));
        assertEquals("Trait\nVerringert: 12,5% (-12,5%, 0,88)",
                TraitDescriptionFormatter.append("Trait", custom, 0.875, null, "de-DE"));
    }

    @Test
    void harvestDescriptionsNeverPromiseNegativeOrAboveCertainBonusChances() {
        TwTraitConfig.TraitDefinition harvest = definition("HarvestDoubleDropChanceMultiplier",
                "{direction} {percent}% ({signedPercent}%)");
        assertEquals("Strength\nChanges 0% (0%)", describe(harvest, 0.8));
        assertEquals("Strength\nIncreases 100% (+100%)", describe(harvest, 2.5));
    }

    @Test
    void flatDispositionUsesPointsAndUnknownEffectsKeepExistingTooltip() {
        TwTraitConfig.TraitDefinition flat = definition("HappinessGainMultiplier", "{direction} {points}");
        assertEquals("Disposition\nDecreases 8",
                TraitDescriptionFormatter.append("Disposition", flat, 1.2, -8.0, "en-US"));
        assertEquals("Strength", describe(definition("CustomEffect", null), 1.2));
    }

    private static String describe(TwTraitConfig.TraitDefinition definition, double value) {
        return TraitDescriptionFormatter.append("Strength", definition, value, null, "en-US");
    }

    private static TwTraitConfig.TraitDefinition definition(String effect, String description) {
        BsonDocument trait = BsonDocument.parse("{\"Id\":\"Test\",\"EffectKey\":\"" + effect + "\"}");
        if (description != null) {
            trait.put("Description", new org.bson.BsonString(description));
        }
        BsonDocument config = new BsonDocument("Traits", new org.bson.BsonArray(java.util.List.of(trait)));
        return TwTraitConfig.CODEC.decode(config, new ExtraInfo()).getTraits()[0];
    }
}
