package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects exact aggregate settlement after a process restart. */
class PopulationAdmissionBatchStagingTest {
    private static final UUID LITTER = UUID.fromString(
            "60000000-0000-0000-0000-000000000601"
    );
    private static final UUID OWNER = UUID.fromString(
            "60000000-0000-0000-0000-000000000602"
    );
    private static final ProfileId PROFILE = ProfileId.parse(
            "60000000-0000-0000-0000-000000000603"
    );

    @TempDir
    Path tempDir;

    @Test
    void liveApplyingBatchCanSettleExactReceiptsAfterStagingRestart() {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration())) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationDomainAdmissionOperation operations = persistence.facades()
                    .operations().populationDomainAdmission();
            List<UUID> provisional = List.of(
                    UUID.nameUUIDFromBytes((LITTER + ":child:0")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    UUID.nameUUIDFromBytes((LITTER + ":child:1")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
            PopulationDomainAdmissionOperation.Payload payload = new PopulationDomainAdmissionOperation.Payload(
                    UUID.nameUUIDFromBytes(("population-domain:litter:" + LITTER
                            + ":reservation")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    PROFILE,
                    new OwnerId(OWNER),
                    null,
                    "world",
                    null,
                    null,
                    null,
                    LifecycleState.ACTIVE,
                    "test-group",
                    "test-provider",
                    1,
                    "generation",
                    1,
                    1,
                    Long.MAX_VALUE,
                    2,
                    List.of(),
                    provisional,
                    1
            );
            OperationId operationId = new OperationId(LITTER);
            PersistenceTransactionResult<OperationEnvelope> prepared = operations.prepare(
                    operationId,
                    new IdempotencyKey("batch-restart-test"),
                    payload
            ).completion().toCompletableFuture().join();
            assertTrue(prepared instanceof PersistenceTransactionResult.Committed<?>);
            operations.claim(operationId).toCompletableFuture().join();

            PopulationAdmissionToken token = new PopulationAdmissionToken(
                    LITTER,
                    payload.reservationId(),
                    Long.MAX_VALUE,
                    1,
                    "generation",
                    OwnerPopulationCapDecisionViewV2.Readiness.READY
            );
            PopulationAdmissionBatchStaging staging = new PopulationAdmissionBatchStaging(
                    operations,
                    new java.util.concurrent.ConcurrentHashMap<>()
            );
            ManagedBatchSettlement settlement = staging.settle(
                    token,
                    Set.of(0),
                    Map.of(0, UUID.fromString(
                            "60000000-0000-0000-0000-000000000604"
                    ))
            ).toCompletableFuture().join();

            assertEquals(ManagedBatchSettlement.Status.COMMITTED, settlement.status(), settlement.reason());
            assertEquals(Set.of(0), settlement.settledOrdinals());
            assertEquals(2, settlement.requestedUnits());
            ManagedBatchSettlement replay = staging.settle(
                    token,
                    Set.of(0),
                    Map.of(0, UUID.fromString(
                            "60000000-0000-0000-0000-000000000604"
                    ))
            ).toCompletableFuture().join();
            assertEquals(settlement.status(), replay.status());
            assertEquals(settlement.actualChildIds(), replay.actualChildIds());
            assertFalse(operations.settlementEvidence(operationId)
                    .toCompletableFuture().join().canceled());
        }
    }

    private PublicPersistenceRuntimeConfiguration configuration() {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "batch-staging-test",
                () -> 1L,
                (claim, operation) -> confirmed("refund"),
                event -> { },
                new PublicPersistenceLiveBoundaries(
                        (request, operation) -> confirmed("capture"),
                        (request, operation) -> confirmed("capture_release"),
                        (request, operation) -> confirmed("restoration"),
                        (request, operation) -> confirmed("coop_capture"),
                        (request, operation) -> confirmed("coop_release")
                ),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }

    private java.util.concurrent.CompletionStage<com.alechilles.alecstamework.persistence.operation.LiveOperationResult>
    confirmed(String code) {
        return com.alechilles.alecstamework.persistence.operation.LiveOperationResult
                .confirmed(code).completed();
    }
}
