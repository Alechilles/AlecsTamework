package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The boundary retries an unavailable world without ever entering the ECS gateway. */
class HytaleCompanionCaptureBoundaryTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");

    @Test
    void unavailableWorldIsRetryableWithoutEcsAccess() throws Exception {
        CompanionCaptureWorldGateway unreachable =
                (world, store, request, operation) -> {
                    throw new AssertionError(
                            "Unavailable capture world cannot reach ECS gateway"
                    );
                };
        HytaleCompanionCaptureBoundary boundary =
                new HytaleCompanionCaptureBoundary(
                        unreachable,
                        ignored -> null
                );

        LiveOperationResult result = boundary.applyOrResolve(
                request(),
                operation()
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals("capture_world_unavailable", result.code());
    }

    private CompanionCaptureRequest request() {
        String payload = "{}";
        return new CompanionCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                null,
                NpcAlias.parse("20000000-0000-0000-0000-000000000001"),
                "world",
                new CompanionSnapshot(
                        SNAPSHOT,
                        PROFILE,
                        CompanionCaptureRequest.SNAPSHOT_KIND,
                        CompanionCaptureRequest.SNAPSHOT_VERSION,
                        payload,
                        Sha256Hash.ofUtf8(payload),
                        new LifecycleRevision(1),
                        true,
                        -700
                ),
                CapturedArtifact.create(
                        "capture-device-filled",
                        1,
                        0.0D,
                        0.0D,
                        "{\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                                + "\":\"" + SNAPSHOT + "\"}"
                ),
                new CaptureSourceEvidence(
                        UUID.fromString(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        2,
                        "capture-device",
                        1,
                        Sha256Hash.ofUtf8("source"),
                        SNAPSHOT.toString()
                ),
                -600
        );
    }

    private OperationEnvelope operation() {
        OperationId operationId = OperationId.parse(
                "60000000-0000-0000-0000-000000000001"
        );
        return new OperationEnvelope(
                operationId,
                new IdempotencyKey("capture-world-test"),
                CompanionCaptureDefinition.KIND,
                2,
                "{}",
                OperationPhase.LIVE_APPLYING,
                "capture-world-test",
                LifecycleRevision.INITIAL,
                null,
                0,
                0,
                null,
                null,
                -600,
                -500,
                null,
                null,
                null,
                List.of(OperationScope.operation(operationId))
        );
    }
}
