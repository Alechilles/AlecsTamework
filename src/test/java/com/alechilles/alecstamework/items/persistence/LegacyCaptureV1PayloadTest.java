package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Released-public capture-v1 payload contract tests. */
class LegacyCaptureV1PayloadTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "83000000-0000-0000-0000-000000000001"
    );
    private static final SnapshotId SNAPSHOT = SnapshotId.parse(
            "83000000-0000-0000-0000-000000000002"
    );

    @Test
    void decodesTheExactReleasedPublicShapeAndSignedTimestamp() {
        LegacyCaptureV1Payload payload = LegacyCaptureV1Payload.decode(
                snapshot("""
                        {"lastKnownPosition":{"x":1.5,"y":-2.0,"z":3},"homePosition":{"x":4,"y":5,"z":6},"capturedAtMs":-99,"roleId":"Tamed_Chicken","displayName":"Chicken"}
                        """.trim())
        );

        assertEquals(-99L, payload.capturedAtMs());
        assertEquals("Tamed_Chicken", payload.roleId());
        assertEquals(1.5D, payload.lastKnownPosition().x());
        assertEquals(6.0D, payload.homePosition().z());
    }

    @Test
    void rejectsReplacementPayloadMislabeledAsVersionOne() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyCaptureV1Payload.decode(snapshot("""
                        {"version":"1","npcUuid":"83000000-0000-0000-0000-000000000003","capturedAtMs":1}
                        """.trim()))
        );
    }

    @Test
    void rejectsMissingTimestampAndNonFiniteVector() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyCaptureV1Payload.decode(snapshot(
                        "{\"roleId\":\"Tamed_Chicken\"}"
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyCaptureV1Payload.decode(snapshot("""
                        {"capturedAtMs":1,"homePosition":{"x":1e9999,"y":2,"z":3}}
                        """.trim()))
        );
    }

    private CompanionSnapshot snapshot(String payload) {
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                LegacyCaptureV1Payload.VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                true,
                -10L
        );
    }
}
