package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay-completeness contracts for the durable resolved-capture event boundary. */
class CaptureAttemptResolutionEventCodecTest {
    private static final OperationId OPERATION = OperationId.parse(
            "10000000-0000-0000-0000-000000000401"
    );
    private static final IdempotencyKey IDEMPOTENCY =
            new IdempotencyKey("capture-operation-key");

    @Test
    void capturedItemPayloadReconstructsPublicEventWithoutStateJoins() {
        CompanionCaptureRequest request = capturedRequest(true);
        String payload = CaptureAttemptResolutionEventCodec.encode(
                OPERATION, IDEMPOTENCY, request, -400L
        );

        com.alechilles.alecstamework.companion.capture
                .CaptureAttemptResolvedEvent decoded =
                CaptureAttemptResolutionEventCodec.decodeEvent(
                        CaptureAttemptResolutionEventCodec.VERSION,
                        payload
                );
        CaptureAttemptResolvedEvent publicEvent =
                CaptureAttemptPublicEventMapper.map(decoded, -399L);

        assertTrue(decoded.replayComplete());
        assertEquals(request, decoded.request());
        assertEquals(OPERATION.value(), publicEvent.operationId());
        assertEquals(request.targetAlias().value(), publicEvent.targetNpcUuid());
        assertEquals(request.profileId().toString(), publicEvent.profileId());
        assertEquals("Dragon_Fire", publicEvent.roleId());
        assertEquals("Ember", publicEvent.replayEvidence().targetDisplayName());
        assertEquals(
                request.resultingOwnerId().value(),
                publicEvent.replayEvidence().ownerUuid()
        );
        assertEquals("hydragon", publicEvent.replayEvidence().callerNamespace());
        assertEquals(
                "encounter-7",
                publicEvent.replayEvidence().callerIdempotencyKey()
        );
        assertEquals(
                IDEMPOTENCY.toString(),
                publicEvent.replayEvidence().operationIdempotencyKey()
        );
        assertEquals(25.0D, publicEvent.currentHealth());
        assertEquals(100.0D, publicEvent.maximumHealth());
        assertEquals(
                request.snapshot().payloadJson(),
                publicEvent.replayEvidence().snapshot().payloadJson()
        );
        assertEquals(
                request.snapshot().payloadHash().toString(),
                publicEvent.replayEvidence().snapshot().payloadHash()
        );
        assertEquals(
                "CAPTURED",
                publicEvent.replayEvidence().lifecycle().state()
        );
        assertEquals(CaptureAttemptOutcome.CAPTURED, publicEvent.outcome());
    }

    @Test
    void absentAbsoluteHealthRemainsExplicitlyAbsent() {
        CompanionCaptureRequest request = capturedRequest(false);
        var decoded = CaptureAttemptResolutionEventCodec.decodeEvent(
                CaptureAttemptResolutionEventCodec.VERSION,
                CaptureAttemptResolutionEventCodec.encode(
                        OPERATION, IDEMPOTENCY, request, -400L
                )
        );

        CaptureAttemptResolvedEvent publicEvent =
                CaptureAttemptPublicEventMapper.map(decoded, -399L);

        assertNull(publicEvent.currentHealth());
        assertNull(publicEvent.maximumHealth());
        assertNull(publicEvent.replayEvidence().formula().currentHealth());
        assertNull(publicEvent.replayEvidence().formula().maximumHealth());
    }

    @Test
    void priorVersionDecodesOnlyAsLegacyAndCannotBePubliclyReplayed() {
        CaptureAttemptResolution resolution = resolution(false);
        var legacy = CaptureAttemptResolutionEventCodec.decodeEvent(
                CaptureAttemptResolutionEventCodec.LEGACY_VERSION,
                CaptureAttemptResolutionEventCodec.encode(
                        UUID.randomUUID(), resolution
                )
        );

        assertFalse(legacy.replayComplete());
        assertEquals(resolution, legacy.resolution());
        assertThrows(
                IllegalArgumentException.class,
                () -> CaptureAttemptPublicEventMapper.map(legacy, -399L)
        );
    }

    private CompanionCaptureRequest capturedRequest(boolean withHealth) {
        ProfileId profile = ProfileId.parse(
                "20000000-0000-0000-0000-000000000401"
        );
        NpcAlias alias = NpcAlias.parse(
                "30000000-0000-0000-0000-000000000401"
        );
        OwnerId owner = OwnerId.parse(
                "40000000-0000-0000-0000-000000000401"
        );
        CaptureAttemptResolution resolution = resolution(withHealth);
        LifecycleRevision expected = new LifecycleRevision(4L);
        String snapshotJson = "{\"full\":\"state\"}";
        CompanionSnapshot snapshot = new CompanionSnapshot(
                new SnapshotId(resolution.attemptId()),
                profile,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CompanionCaptureRequest.SNAPSHOT_VERSION,
                snapshotJson,
                Sha256Hash.ofUtf8(snapshotJson),
                expected.next(),
                true,
                -500L
        );
        String metadata = "{\""
                + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                + "\":\"" + resolution.attemptId() + "\"}";
        return new CompanionCaptureRequest(
                profile,
                expected,
                owner,
                alias,
                "encounter-world",
                new CaptureTerminalPlan.CapturedItem(
                        resolution,
                        new CompanionSnapshotEvidence(
                                snapshot,
                                CapturedArtifact.create(
                                        "HyDragon_Draconic_Stone_Filled",
                                        1, 0.0D, 0.0D, metadata
                                )
                        )
                ),
                new CaptureSourceEvidence(
                        owner.value(),
                        "encounter-world",
                        3,
                        "HyDragon_Draconic_Stone",
                        2,
                        Sha256Hash.ofUtf8("source-before"),
                        1,
                        Sha256Hash.ofUtf8("source-after"),
                        resolution.attemptId().toString()
                ),
                -600L
        );
    }

    private CaptureAttemptResolution resolution(boolean withHealth) {
        return new CaptureAttemptResolution(
                UUID.fromString(
                        "50000000-0000-0000-0000-000000000401"
                ),
                "Dragon_Fire",
                new CaptureAttemptFormula(
                        "HyDragonDraconicStone",
                        7L,
                        CaptureChanceMode.PROBABILITY,
                        4,
                        0.2D,
                        0.1D,
                        0.05D,
                        0.95D,
                        "HyDragonDragonCapture",
                        11L,
                        2,
                        0.1D,
                        0.8D,
                        0.5D,
                        8,
                        Sha256Hash.ofUtf8("requirements"),
                        13L
                ),
                CaptureSourceConsumption.RESOLVED_ATTEMPT,
                CaptureSuccessDisposition.CAPTURED_ITEM,
                CaptureAttemptResolution.Outcome.SUCCESS,
                "capture-probability-success",
                0.35D,
                false,
                0.75D,
                0.2D,
                null,
                "hydragon",
                "encounter-7",
                "Ember",
                withHealth ? 25.0D : null,
                withHealth ? 100.0D : null
        );
    }
}
