package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionTalentServiceAmountResolverTest {
    @Test
    void restoredPurchasedTalentIdsContributeTheirAdditiveHappinessBonusOnly() {
        TwTalentConfig config = TwTalentConfig.CODEC.decode(BsonDocument.parse("""
                {"Enabled":true,"Talents":[
                  {"Id":"Care_A","Effects":[{"EffectKey":"HappinessFlatBonus","Multiplier":1.4,"Amount":2.3}]},
                  {"Id":"Care_B","Effects":[{"EffectKey":"HappinessFlatBonus","Multiplier":1.2,"Amount":3.7}]},
                  {"Id":"Other","Effects":[{"EffectKey":"FertilityMultiplier","Multiplier":1.2}]}
                ]}
                """), new ExtraInfo());

        assertEquals(6.0, CompanionTalentService.resolvePurchasedEffectAmount(
                config, new String[] {"Care_A", "Care_B", "Other"}, "HappinessFlatBonus"), 0.000001);
        assertEquals(0.0, CompanionTalentService.resolvePurchasedEffectAmount(
                config, new String[] {"Care_A", "Care_B", "Other"}, "FertilityMultiplier"), 0.000001);
        assertEquals(1.2, CompanionTalentService.resolvePurchasedEffectMultiplier(
                config, new String[] {"Care_A", "Care_B", "Other"}, "FertilityMultiplier", 1.0), 0.000001);
    }
}
