package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards lossless and integrity-checked full entity checkpoints. */
class CompanionEntityCheckpointCodecTest {
    private final CompanionEntityCheckpointCodec codec =
            new CompanionEntityCheckpointCodec();

    @Test
    void roundTripPreservesUnknownNestedSerializableComponents() {
        BsonDocument holder = BsonDocument.parse("""
                {
                  "UUID":{"UUID":{"$binary":{"base64":"AAAAAAAAAAAAAAAAAAAAAQ==","subType":"04"}}},
                  "Model":{"Model":{"Id":"Cat","Scale":0.91,"RandomAttachments":{"Coat":"Brown_Tuxedo","Eyes":"ForestGreen"}}},
                  "ThirdParty:Genetics":{"alleles":["A","b"],"nested":{"generation":7}},
                  "Inventory":{"slots":[{"item":"Lantern","quantity":2}]}
                }
                """);
        CompanionEntityCheckpoint checkpoint = checkpoint(holder);

        CompanionEntityCheckpoint decoded = codec.decode(
                codec.encode(checkpoint)
        );

        assertEquals(checkpoint.profileId(), decoded.profileId());
        assertEquals(checkpoint.alias(), decoded.alias());
        assertEquals(checkpoint.sourceAlias(), decoded.sourceAlias());
        assertEquals(holder, decoded.holder());
        assertTrue(decoded.payloadHash().matchesUtf8(
                codec.integrityMaterial(decoded)
        ));
    }

    @Test
    void rejectsTamperedHolderPayload() {
        String encoded = codec.encode(checkpoint(BsonDocument.parse(
                "{\"Model\":{\"Scale\":1.0}}"
        )));

        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                encoded.replace("1.0", "2.0")
        ));
    }

    private CompanionEntityCheckpoint checkpoint(BsonDocument holder) {
        return CompanionEntityCheckpoint.create(
                ProfileId.parse("20000000-0000-0000-0000-000000000001"),
                NpcAlias.parse("30000000-0000-0000-0000-000000000001"),
                4,
                OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                new LifecycleRevision(8),
                new ReconciliationGeneration(3),
                "default",
                12.5,
                65.0,
                -9.25,
                CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                -6_000,
                holder,
                codec
        );
    }
}
