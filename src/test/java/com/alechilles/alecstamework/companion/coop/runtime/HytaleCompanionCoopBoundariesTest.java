package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Both coop boundaries share unavailable-world handling without touching ECS. */
class HytaleCompanionCoopBoundariesTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "coop", 10, 64, 20, 0);

    @Test
    void unavailableCaptureWorldIsRetryableWithoutEcsAccess()
            throws Exception {
        HytaleWorldOperationDispatcher dispatcher =
                new HytaleWorldOperationDispatcher(ignored -> null);
        HytaleCompanionCoopCaptureBoundary boundary =
                new HytaleCompanionCoopCaptureBoundary(
                        unreachableCaptureGateway(),
                        dispatcher
                );

        LiveOperationResult result = boundary.applyOrResolve(
                captureRequest(), operation("companion_coop_capture")
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals("coop_capture_world_unavailable", result.code());
    }

    @Test
    void unavailableReleaseWorldIsRetryableWithoutEcsAccess()
            throws Exception {
        HytaleWorldOperationDispatcher dispatcher =
                new HytaleWorldOperationDispatcher(ignored -> null);
        HytaleCompanionCoopReleaseBoundary boundary =
                new HytaleCompanionCoopReleaseBoundary(
                        unreachableReleaseGateway(),
                        dispatcher
                );

        LiveOperationResult result = boundary.applyOrResolve(
                releaseRequest(), operation("companion_coop_release")
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals("coop_release_world_unavailable", result.code());
    }

    private CompanionCoopCaptureWorldGateway
    unreachableCaptureGateway() {
        return (world, store, request, operation) -> {
            throw new AssertionError(
                    "Unavailable capture world cannot reach ECS gateway"
            );
        };
    }

    private CompanionCoopReleaseWorldGateway
    unreachableReleaseGateway() {
        return (world, store, request, operation) -> {
            throw new AssertionError(
                    "Unavailable release world cannot reach ECS gateway"
            );
        };
    }

    private CompanionCoopCaptureRequest captureRequest() {
        return new CompanionCoopCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                SLOT,
                snapshot(new LifecycleRevision(1)),
                new CoopCaptureSourceEvidence(
                        SOURCE_ALIAS, "world", "capture-receipt"
                ),
                -600
        );
    }

    private CompanionCoopReleaseRequest releaseRequest() {
        CompanionSnapshot snapshot = snapshot(new LifecycleRevision(1));
        return new CompanionCoopReleaseRequest(
                PROFILE,
                new LifecycleRevision(2),
                new CoopResidency(
                        SLOT, PROFILE, SOURCE_ALIAS, SNAPSHOT, -700, -700
                ),
                snapshot,
                TARGET_ALIAS,
                "world-two",
                "spawn-receipt",
                -600
        );
    }

    private CompanionSnapshot snapshot(LifecycleRevision sourceRevision) {
        String payload = "{\"health\":100}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                sourceRevision,
                true,
                -700
        );
    }

    private OperationEnvelope operation(String kind) {
        OperationId operationId = OperationId.parse(
                "60000000-0000-0000-0000-000000000001"
        );
        return new OperationEnvelope(
                operationId,
                new IdempotencyKey(kind + "-world-test"),
                new OperationKind(kind),
                1,
                "{}",
                OperationPhase.LIVE_APPLYING,
                kind,
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
