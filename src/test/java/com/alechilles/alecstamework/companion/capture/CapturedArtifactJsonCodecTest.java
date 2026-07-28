package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical, type-preserving captured-artifact value and codec contracts. */
class CapturedArtifactJsonCodecTest {
    private static final String UNSORTED_METADATA = """
            {
              "zLong":{"$numberLong":"5"},
              "aDocument":{
                "zDouble":{"$numberDouble":"1.5"},
                "aInt":{"$numberInt":"3"}
              },
              "mArray":[
                {"z":{"$numberLong":"8"},"a":{"$numberInt":"1"}},
                {"$numberLong":"9"}
              ]
            }
            """;

    @Test
    void roundTripPreservesBsonTypesAndRecursivelyCanonicalizesObjects() {
        CapturedArtifact artifact = CapturedArtifact.create(
                "capture-device-filled",
                3,
                7.25D,
                20.0D,
                UNSORTED_METADATA
        );

        CapturedArtifact decoded = CapturedArtifactJsonCodec.decode(
                CapturedArtifactJsonCodec.encode(artifact)
        );
        BsonDocument metadata = BsonDocument.parse(
                decoded.metadataExtendedJson()
        );
        BsonDocument nested = metadata.getDocument("aDocument");
        BsonArray array = metadata.getArray("mArray");

        assertEquals(artifact, decoded);
        assertEquals(
                List.of("aDocument", "mArray", "zLong"),
                List.copyOf(metadata.keySet())
        );
        assertEquals(
                List.of("aInt", "zDouble"),
                List.copyOf(nested.keySet())
        );
        assertEquals(
                List.of("a", "z"),
                List.copyOf(array.get(0).asDocument().keySet())
        );
        assertTrue(metadata.get("zLong").isInt64());
        assertTrue(nested.get("aInt").isInt32());
        assertTrue(nested.get("zDouble").isDouble());
        assertTrue(array.get(1).isInt64());
    }

    @Test
    void equivalentObjectOrderProducesOneCanonicalHashWhileArrayOrderRemainsSignificant() {
        CapturedArtifact first = CapturedArtifact.create(
                "capture-device-filled",
                1,
                0.0D,
                0.0D,
                "{\"b\":{\"y\":2,\"x\":1},\"a\":[2,1]}"
        );
        CapturedArtifact reorderedObjects = CapturedArtifact.create(
                "capture-device-filled",
                1,
                0.0D,
                0.0D,
                "{\"a\":[2,1],\"b\":{\"x\":1,\"y\":2}}"
        );
        CapturedArtifact reorderedArray = CapturedArtifact.create(
                "capture-device-filled",
                1,
                0.0D,
                0.0D,
                "{\"a\":[1,2],\"b\":{\"x\":1,\"y\":2}}"
        );

        assertEquals(
                first.metadataExtendedJson(),
                reorderedObjects.metadataExtendedJson()
        );
        assertEquals(first.artifactHash(), reorderedObjects.artifactHash());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                first.artifactHash(),
                reorderedArray.artifactHash()
        );
    }

    @Test
    void constructorRejectsEveryHashCoveredFieldWhenTampered() {
        CapturedArtifact artifact = CapturedArtifact.create(
                "capture-device-filled",
                2,
                4.0D,
                8.0D,
                "{\"value\":{\"$numberLong\":\"7\"}}"
        );

        assertTampered(
                "other-item",
                artifact.quantity(),
                artifact.durability(),
                artifact.maxDurability(),
                artifact.metadataExtendedJson(),
                artifact.artifactHash()
        );
        assertTampered(
                artifact.itemId(),
                3,
                artifact.durability(),
                artifact.maxDurability(),
                artifact.metadataExtendedJson(),
                artifact.artifactHash()
        );
        assertTampered(
                artifact.itemId(),
                artifact.quantity(),
                5.0D,
                artifact.maxDurability(),
                artifact.metadataExtendedJson(),
                artifact.artifactHash()
        );
        assertTampered(
                artifact.itemId(),
                artifact.quantity(),
                artifact.durability(),
                9.0D,
                artifact.metadataExtendedJson(),
                artifact.artifactHash()
        );
        assertTampered(
                artifact.itemId(),
                artifact.quantity(),
                artifact.durability(),
                artifact.maxDurability(),
                "{\"value\":{\"$numberLong\":\"8\"}}",
                artifact.artifactHash()
        );
    }

    @Test
    void invalidValuesAndNoncanonicalDirectMetadataFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturedArtifact.create("", 1, 0.0D, 0.0D, "{}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturedArtifact.create("Empty", 1, 0.0D, 0.0D, "{}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturedArtifact.create("item", 0, 0.0D, 0.0D, "{}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturedArtifact.create(
                        "item", 1, Double.NaN, 0.0D, "{}"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturedArtifact.create(
                        "item", 1, 0.0D, -1.0D, "{}"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturedArtifact.create("item", 1, 0.0D, 0.0D, "[]")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturedArtifact.create("item", 1, 0.0D, 0.0D, "{")
        );

        CapturedArtifact canonical = CapturedArtifact.create(
                "item", 1, 0.0D, 0.0D, "{\"a\":1,\"b\":2}"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapturedArtifact(
                        canonical.itemId(),
                        canonical.quantity(),
                        canonical.durability(),
                        canonical.maxDurability(),
                        "{\"b\":2,\"a\":1}",
                        canonical.artifactHash()
                )
        );
    }

    private void assertTampered(
            String itemId,
            int quantity,
            double durability,
            double maxDurability,
            String metadata,
            Sha256Hash hash
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapturedArtifact(
                        itemId,
                        quantity,
                        durability,
                        maxDurability,
                        metadata,
                        hash
                )
        );
    }
}
