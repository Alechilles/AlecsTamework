package com.alechilles.alecstamework.companion.restoration.runtime;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** World lookup failure remains a retry and never invokes the ECS gateway. */
class HytaleCompanionRestorationBoundaryTest {
    @Test
    void unavailableCurrentWorldIsRetryableWithoutEcsAccess()
            throws Exception {
        HytaleCompanionRestorationBoundary boundary =
                new HytaleCompanionRestorationBoundary(
                        (world, store, request, operation) -> {
                            throw new AssertionError(
                                    "Unavailable world cannot reach ECS gateway"
                            );
                        },
                        ignored -> null
                );

        LiveOperationResult result = boundary.applyOrResolve(
                request(), operation()
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals("restoration_world_unavailable", result.code());
    }

    private CompanionRestorationRequest request() {
        String payload = "{\"health\":100}";
        String projectionPayload = "{\"state\":\"frozen\"}";
        return new CompanionRestorationRequest(
                ProfileId.parse(
                        "10000000-0000-0000-0000-000000000001"
                ),
                new LifecycleRevision(1),
                LifecycleState.DEAD_REVIVABLE,
                new CompanionSnapshot(
                        SnapshotId.parse(
                                "50000000-0000-0000-0000-000000000001"
                        ),
                        ProfileId.parse(
                                "10000000-0000-0000-0000-000000000001"
                        ),
                        DormantSourceEvidence.Kind.DEATH_COMPONENT
                                .snapshotKind(),
                        1,
                        payload,
                        Sha256Hash.ofUtf8(payload),
                        LifecycleRevision.INITIAL,
                        true,
                        -500
                ),
                new RestorationProjection(
                        NpcAlias.parse(
                                "20000000-0000-0000-0000-000000000002"
                        ),
                        new SnapshotCodecRegistry.EncodedSnapshot(
                                CompanionFullStateProjection.KIND,
                                CompanionFullStateProjection.VERSION,
                                projectionPayload,
                                Sha256Hash.ofUtf8(projectionPayload)
                        )
                ),
                NpcAlias.parse(
                        "20000000-0000-0000-0000-000000000001"
                ),
                new CompanionSpawnPlacement(
                        "world", -1, -2, -3, 0, -1, 0
                ),
                "spawn-receipt",
                -600
        );
    }

    private OperationEnvelope operation() {
        OperationId operationId = OperationId.parse(
                "60000000-0000-0000-0000-000000000001"
        );
        return new OperationEnvelope(
                operationId,
                new IdempotencyKey("restoration-world-test"),
                new OperationKind("companion_restoration"),
                3,
                "{}",
                OperationPhase.LIVE_APPLYING,
                "companion_restoration",
                new LifecycleRevision(1),
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
