package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract coverage for crash-safe restoration projection JSON. */
class RestorationProjectionJsonCodecTest {
    @Test
    void exactProjectionRoundTripsWithIntegrityEvidence() {
        String payload = "{\"version\":\"1\",\"npcUuid\":\""
                + "00000000-0000-0000-0000-000000000001\"}";
        RestorationProjection projection = new RestorationProjection(
                new NpcAlias(UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"
                )),
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        payload,
                        Sha256Hash.ofUtf8(payload)
                )
        );

        JsonObject encoded = RestorationProjectionJsonCodec.encode(projection);

        assertEquals(
                projection,
                RestorationProjectionJsonCodec.decode(encoded)
        );
        assertEquals(
                java.util.Set.of("sourceAlias", "fullState"),
                encoded.keySet()
        );
    }

    @Test
    void rejectsUnknownFieldsAndMismatchedPayloadHash() {
        JsonObject json = new JsonObject();
        json.addProperty(
                "sourceAlias",
                "00000000-0000-0000-0000-000000000001"
        );
        JsonObject fullState = new JsonObject();
        fullState.addProperty("kind", "death");
        fullState.addProperty("payloadVersion", 2);
        fullState.addProperty("payloadJson", "{}");
        fullState.addProperty(
                "payloadHash",
                Sha256Hash.ofUtf8("{\"different\":true}").toString()
        );
        json.add("fullState", fullState);

        assertThrows(
                IllegalArgumentException.class,
                () -> RestorationProjectionJsonCodec.decode(json)
        );
        json.addProperty("extra", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> RestorationProjectionJsonCodec.decode(json)
        );
    }
}
