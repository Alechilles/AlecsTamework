package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BondedTalentTimersTest {
    /** An imported companion's first summon must already get the session its bought talents give. */
    @Test
    void timerTalentsInAnImportedStateSnapshotAdjustTheFamilysTimers() {
        BondedCompanionPolicy family = new BondedCompanionPolicy(1L, "test:roster", "test:family",
                Set.of("test:role"), 1, 1, 300L, 1_800L, null, null,
                new BondedCompanionPolicy.FeatureFlags(true, true, true, true, true));
        TwTalentConfig config = TwTalentConfig.CODEC.decode(BsonDocument.parse("""
                { "Enabled": true, "Talents": [
                  { "Id": "longer", "Effects": [
                    { "EffectKey": "SummonSessionDurationMultiplier", "Multiplier": 2.0 }
                  ] },
                  { "Id": "faster", "Effects": [
                    { "EffectKey": "SummonCooldownMultiplier", "Multiplier": 0.5 }
                  ] }
                ] }
                """), new ExtraInfo());
        UUID profileId = UUID.randomUUID();
        String json = new CoopResidentStateSnapshotCodec().encode(new CoopResidentStateSnapshot(UUID.randomUUID(),
                null, -1, "test:role", null, null, null, null, null, null, null, null, null,
                new TameworkTalentsComponent("test:talents", 10, new String[] {"longer", "faster"}), null, null,
                null, null, null, 1L));

        BondedCompanionPolicy adjusted = BondedTalentTimers.adjust(family,
                SnapshotEnvelope.importedState(profileId, 0L, json), config);

        assertEquals(600L, adjusted.sessionDurationSeconds());
        assertEquals(900L, adjusted.summonCooldownSeconds());
    }
}
