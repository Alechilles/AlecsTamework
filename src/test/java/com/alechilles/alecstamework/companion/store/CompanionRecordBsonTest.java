package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.RecordScope;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.List;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionRecordBsonTest {
    @Test
    void everyFieldSurvivesARoundTripThroughJsonText() {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), "Tamed_Sheep", CompanionLocation.stored(StoredReason.BONDED))
                .revision(0).generation(7)
                .ownerUuid(UUID.randomUUID()).ownerName("Alec")
                .displayName("Wooly").homeWorld("default")
                .currentNpcUuid(UUID.randomUUID())
                .summary(new CompanionSummary("Wooly", "server.npcs.sheep.name", "Tamed_Sheep", "Icon_Sheep",
                        12.5f, 20f, "TwHappinessDefault", 55.5, "TwNeedsDefault", 40.0, 35.25,
                        true, true, -3000L, -9000L, 6000L, 123456L,
                        "TwLevelingDefault", 4, 12.5, 340.0, 2,
                        java.util.Map.of("speed", 1.25, "strength", 0.75),
                        1_700_000_000_000L, -4000L, 2500L, "TwTraitsDefault",
                        "TwTalentsDefault", new CompanionSummary.Progression("Adult", -900L, -600L, -300L, true,
                                660_000.0, UUID.randomUUID().toString(), 12_345L, true, -1_000_000L, -250L, true, true,
                                5_000L)))
                .rosterId("hydragon:dragon_horn").rosterSlot(3).bonded(true)
                .summonedUntilMs(-123456789L).summonCooldownUntilMs(0L).reviveAvailableAtMs(-5L).diedAtMs(42L).lastSnapshotAtMs(99L)
                .origin("Alechilles:HyDragon", "soul-1")
                .toolIds(List.of("tool-a", "tool-b"))
                .extension("Alechilles:HyDragon/progress", new ExtensionEntry(2, "{\"xp\":10}"))
                .domainClaims(List.of(new DomainClaim("runeteria:husbandry_deployable", 1, false, true)))
                .updatedAtMs(1_700_000_000_000L)
                .build();

        BsonDocument viaJson = BsonDocument.parse(CompanionRecordBson.encode(record).toJson());

        assertEquals(record, CompanionRecordBson.decode(viaJson));

        CompanionRecord slotOnly = CompanionRecord.builder(UUID.randomUUID(), "Tamed_Sheep", CompanionLocation.item())
                .rosterSlot(2).build();
        assertEquals(slotOnly, CompanionRecordBson.decode(BsonDocument.parse(CompanionRecordBson.encode(slotOnly).toJson())));
    }

    @Test
    void aRevisionOrGenerationThatIsNotANumberIsUnreadableRatherThanZero() {
        for (String field : List.of("Revision", "Generation")) {
            BsonDocument wrong = new BsonDocument("ProfileId", new BsonString(UUID.randomUUID().toString()))
                    .append("Role", new BsonString("Sheep"))
                    .append("Location", new BsonDocument("Kind", new BsonString("ITEM")))
                    .append(field, new BsonString("7"));

            assertThrows(IllegalArgumentException.class, () -> CompanionRecordBson.decode(wrong), field);
        }
    }

    @Test
    void aRecordWrittenWithFewerFieldsLoadsWithDefaults() {
        UUID id = UUID.randomUUID();
        BsonDocument minimal = new BsonDocument("ProfileId", new BsonString(id.toString()))
                .append("Role", new BsonString("Sheep"))
                .append("Location", new BsonDocument("Kind", new BsonString("ITEM")));

        CompanionRecord decoded = CompanionRecordBson.decode(minimal);

        assertEquals(id, decoded.profileId());
        assertEquals(0, decoded.revision());
        assertEquals(RecordScope.WORLD_BOUND, decoded.scope());
        assertEquals(CompanionSummary.EMPTY, decoded.summary());
    }

    @Test
    void anUnknownLocationKindIsUnreadableRatherThanGuessed() {
        BsonDocument future = new BsonDocument("ProfileId", new BsonString(UUID.randomUUID().toString()))
                .append("Role", new BsonString("Sheep"))
                .append("Location", new BsonDocument("Kind", new BsonString("TELEPORTING")));

        assertThrows(IllegalArgumentException.class, () -> CompanionRecordBson.decode(future));
    }

    @Test
    void anOwnerOfTheWrongTypeIsUnreadableRatherThanDropped() {
        BsonDocument wrongOwner = new BsonDocument("ProfileId", new BsonString(UUID.randomUUID().toString()))
                .append("Role", new BsonString("Sheep"))
                .append("Location", new BsonDocument("Kind", new BsonString("ITEM")))
                .append("Owner", new BsonInt64(42));

        assertThrows(IllegalArgumentException.class, () -> CompanionRecordBson.decode(wrongOwner));
    }

    /** A stored claim that cannot count is dropped, so the record and its owner file stay readable. */
    @Test
    void aStoredDomainClaimWithoutAPositiveWeightIsDroppedAndTheRecordStillLoads() {
        UUID id = UUID.randomUUID();
        BsonArray claims = new BsonArray(List.of(claim("pasture", 0), claim("barn", 2)));
        BsonDocument stored = new BsonDocument("ProfileId", new BsonString(id.toString()))
                .append("Role", new BsonString("Sheep"))
                .append("Location", new BsonDocument("Kind", new BsonString("ITEM")))
                .append("Revision", new BsonInt64(1)).append("Generation", new BsonInt64(1))
                .append("DomainClaims", claims);

        CompanionRecord decoded = CompanionRecordBson.decode(stored);

        assertEquals(id, decoded.profileId());
        assertEquals(List.of(new DomainClaim("barn", 2, true, false)), decoded.domainClaims());
    }

    private static BsonDocument claim(String domain, long weight) {
        return new BsonDocument("Domain", new BsonString(domain)).append("Weight", new BsonInt64(weight))
                .append("Owned", BsonBoolean.TRUE).append("Deployable", BsonBoolean.FALSE);
    }
}
