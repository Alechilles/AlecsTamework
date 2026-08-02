package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BondedCompanionTalentTimerPolicyModifierTest {
    @Test
    void appliesPurchasedTimerTalentsToFinitePolicyTimers() {
        BondedCompanionPolicy adjusted = BondedCompanionTalentTimerPolicyModifier.apply(
                policy(300L, 1_800L),
                new TameworkTalentsComponent("test:talents", 10,
                        new String[] { "longer", "faster" }),
                talents()
        );

        assertEquals(600L, adjusted.sessionDurationSeconds());
        assertEquals(900L, adjusted.summonCooldownSeconds());
    }

    @Test
    void leavesUnlimitedTimersAndUnpurchasedEffectsUnchanged() {
        BondedCompanionPolicy adjusted = BondedCompanionTalentTimerPolicyModifier.apply(
                policy(0L, 0L),
                new TameworkTalentsComponent("test:talents", 0,
                        new String[] { "unknown" }),
                talents()
        );

        assertEquals(0L, adjusted.sessionDurationSeconds());
        assertEquals(0L, adjusted.summonCooldownSeconds());
    }

    private static BondedCompanionPolicy policy(long duration, long cooldown) {
        return new BondedCompanionPolicy(
                1L, "test:roster", "test:family", Set.of("test:role"),
                1, 1, duration, cooldown, null, null,
                new BondedCompanionPolicy.FeatureFlags(true, true, true,
                        true, true)
        );
    }

    private static TwTalentConfig talents() {
        return TwTalentConfig.CODEC.decode(BsonDocument.parse("""
                { "Enabled": true, "Talents": [
                  { "Id": "longer", "Effects": [
                    { "EffectKey": "SummonSessionDurationMultiplier", "Multiplier": 2.0 }
                  ] },
                  { "Id": "faster", "Effects": [
                    { "EffectKey": "SummonCooldownMultiplier", "Multiplier": 0.5 }
                  ] }
                ] }
                """), new ExtraInfo());
    }
}
