package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Capture payload codec and cross-authority validation contracts. */
class CompanionCaptureDefinitionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final LifecycleRevision EXPECTED = new LifecycleRevision(4);
    private static final String PAYLOAD = "{\"capturedAtMs\":-500}";
    private static final String SNAPSHOT_ID =
            "50000000-0000-0000-0000-000000000001";

    @Test
    void versionTwoRoundTripsSignedEvidenceExactly() throws Exception {
        CompanionCaptureRequest request = request(PROFILE, EXPECTED);

        String encoded = CompanionCaptureDefinition.INSTANCE.encode(request);
        CompanionCaptureRequest decoded =
                CompanionCaptureDefinition.INSTANCE.decode(encoded);

        assertEquals(2, CompanionCaptureDefinition.INSTANCE.payloadVersion());
        assertEquals(request, decoded);
        CompanionCaptureOutcome outcome = new CompanionCaptureOutcome(
                PROFILE,
                request.snapshot().snapshotId(),
                new LifecycleRevision(6),
                request.source().receiptKey(),
                -500
        );
        assertEquals(
                outcome,
                CompanionCaptureEventCodec.decode(
                        CompanionCaptureEventCodec.VERSION,
                        CompanionCaptureEventCodec.encode(outcome)
                )
        );
    }

    @Test
    void versionTwoRoundTripsExactStackRemainder() throws Exception {
        CompanionCaptureRequest singleton = request(PROFILE, EXPECTED);
        CaptureSourceEvidence source = singleton.source();
        CompanionCaptureRequest stacked = new CompanionCaptureRequest(
                singleton.profileId(),
                singleton.expectedLifecycleRevision(),
                singleton.resultingOwnerId(),
                singleton.targetAlias(),
                singleton.targetWorldKey(),
                singleton.terminal(),
                new CaptureSourceEvidence(
                        source.actorUuid(),
                        source.worldKey(),
                        source.slot(),
                        source.sourceItemId(),
                        8,
                        Sha256Hash.ofUtf8("stack-before"),
                        7,
                        Sha256Hash.ofUtf8("stack-after"),
                        source.receiptKey()
                ),
                singleton.requestedAtMs()
        );

        assertEquals(
                stacked,
                CompanionCaptureDefinition.INSTANCE.decode(
                        CompanionCaptureDefinition.INSTANCE.encode(stacked)
                )
        );
    }

    @Test
    void snapshotMustBelongToProfileAndPostPrepareRevision() {
        ProfileId other =
                ProfileId.parse("10000000-0000-0000-0000-000000000002");

        assertThrows(
                IllegalArgumentException.class,
                () -> request(other, EXPECTED)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionCaptureRequest(
                        PROFILE,
                        EXPECTED,
                        null,
                        NpcAlias.parse("20000000-0000-0000-0000-000000000001"),
                        "world",
                        snapshot(PROFILE, EXPECTED),
                        artifact(),
                        new CaptureSourceEvidence(
                                UUID.randomUUID(),
                                "other-world",
                                2,
                                "capture-device",
                                1,
                                Sha256Hash.ofUtf8("before"),
                                SNAPSHOT_ID
                        ),
                        -600
                )
        );
    }

    @Test
    void receiptMustBeTheCaptureSnapshotIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionCaptureRequest(
                        PROFILE,
                        EXPECTED,
                        null,
                        NpcAlias.parse(
                                "20000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        snapshot(PROFILE, EXPECTED),
                        artifact(),
                        new CaptureSourceEvidence(
                                UUID.randomUUID(),
                                "world",
                                2,
                                "capture-device",
                                1,
                                Sha256Hash.ofUtf8("before"),
                                "not-the-snapshot"
                        ),
                        -600
                )
        );
    }

    @Test
    void artifactReceiptMustBeTheExactStringCaptureReceipt() {
        assertInvalidArtifactReceipt("{}");
        assertInvalidArtifactReceipt(
                "{\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID + "\":7}"
        );
        assertInvalidArtifactReceipt(
                "{\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                        + "\":\"not-the-snapshot\"}"
        );
    }

    private CompanionCaptureRequest request(
            ProfileId requestedProfile,
            LifecycleRevision expected
    ) {
        return new CompanionCaptureRequest(
                requestedProfile,
                expected,
                OwnerId.parse("30000000-0000-0000-0000-000000000001"),
                NpcAlias.parse("20000000-0000-0000-0000-000000000001"),
                "world",
                snapshot(PROFILE, expected),
                artifact(),
                new CaptureSourceEvidence(
                        UUID.fromString("40000000-0000-0000-0000-000000000001"),
                        "world",
                        2,
                        "capture-device",
                        1,
                        Sha256Hash.ofUtf8("before-fingerprint"),
                        SNAPSHOT_ID
                ),
                -600
        );
    }

    private CompanionSnapshot snapshot(
            ProfileId profileId,
            LifecycleRevision expected
    ) {
        return new CompanionSnapshot(
                SnapshotId.parse(SNAPSHOT_ID),
                profileId,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CompanionCaptureRequest.SNAPSHOT_VERSION,
                PAYLOAD,
                Sha256Hash.ofUtf8(PAYLOAD),
                expected.next(),
                true,
                -500
        );
    }

    private CapturedArtifact artifact() {
        return artifact(
                "{\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID + "\":\""
                        + SNAPSHOT_ID + "\"}"
        );
    }

    private CapturedArtifact artifact(String metadata) {
        return CapturedArtifact.create(
                "capture-device-filled",
                1,
                0.0D,
                0.0D,
                metadata
        );
    }

    private void assertInvalidArtifactReceipt(String metadata) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionCaptureRequest(
                        PROFILE,
                        EXPECTED,
                        null,
                        NpcAlias.parse(
                                "20000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        snapshot(PROFILE, EXPECTED),
                        artifact(metadata),
                        new CaptureSourceEvidence(
                                UUID.randomUUID(),
                                "world",
                                2,
                                "capture-device",
                                1,
                                Sha256Hash.ofUtf8("before"),
                                SNAPSHOT_ID
                        ),
                        -600
                )
        );
    }
}
