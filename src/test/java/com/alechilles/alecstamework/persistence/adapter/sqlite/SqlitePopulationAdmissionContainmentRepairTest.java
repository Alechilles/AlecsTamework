package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.*;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.*;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.*;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlitePopulationAdmissionContainmentRepairTest {
    @TempDir Path directory;
    private static final OwnerId OWNER = new OwnerId(UUID.randomUUID());

    @Test void startupNarrowsLegacyLocksWithoutReleasingCapacity() throws Exception {
        verifyRepair(false);
    }

    @Test void missingReservationKeepsLegacyOwnerProtected() throws Exception {
        verifyRepair(true);
    }

    private void verifyRepair(boolean removeReservation) throws Exception {
        var connections = new SqliteConnectionFactory(directory.resolve("state.sqlite"));
        new SqliteSchemaV2Manager(connections, () -> 1).initialize();
        var kernel = new SqlitePersistenceKernel(connections);
        try {
            var registry = PublicPersistenceFeatureRegistry.create();
            var adapter = new SqlitePublicPersistenceAdapter(registry, kernel,
                    PersistenceOperationAdmissionGate.allowAll(), () -> 100,
                    (claim, operation) -> LiveOperationResult.confirmed("refund").completed(), event -> {});
            var engine = new SqliteOperationEngine(registry.operationDefinitions(), kernel.units());
            var id = OperationId.create();
            var payload = payload(new ProfileId(UUID.randomUUID()));
            var admissions = adapter.populationDomainAdmissionOperations();
            assertInstanceOf(PersistenceTransactionResult.Committed.class, admissions.prepare(id,
                    new IdempotencyKey("legacy-admin"), payload).completion().toCompletableFuture().join());
            var applying = admissions.claim(id).toCompletableFuture().join();
            var unknown = ((PersistenceTransactionResult.Committed<OperationEnvelope>) engine.transition(applying,
                    OperationPhase.UNKNOWN, "LIVE_OUTCOME_UNKNOWN", "domain_admission_live_effect_unreadable", 2)
                    .completion().toCompletableFuture().join()).value();
            assertInstanceOf(PersistenceTransactionResult.Committed.class, engine.containUnknown(unknown,
                    SqlitePopulationAdmissionContainmentRepair.REASON, "legacy containment",
                    unknown.participants(), 3).completion().toCompletableFuture().join());
            if (removeReservation) {
                try (var connection = connections.openWriterConnection()) {
                    assertTrue(new SqlitePopulationDomainStore(connection).retireExact(id, 1));
                }
            }
            adapter.loadCanonical().toCompletableFuture().join();
            adapter.loadCanonical().toCompletableFuture().join();
            try (var connection = connections.openReadConnection()) {
                var transaction = new SqlitePersistenceTransactionContext(connection);
                assertEquals(OperationPhase.UNKNOWN, transaction.operations().find(id).orElseThrow().phase());
                var ownerFence = transaction.incidents().findQuarantine(OperationScope.owner(OWNER)).orElseThrow();
                assertEquals(removeReservation ? QuarantineState.ACTIVE : QuarantineState.RELEASED, ownerFence.state());
                assertEquals(QuarantineState.ACTIVE, transaction.incidents()
                        .findQuarantine(OperationScope.profile(payload.profileId())).orElseThrow().state());
                assertEquals(QuarantineState.ACTIVE, transaction.incidents()
                        .findQuarantine(OperationScope.operation(id)).orElseThrow().state());
                if (!removeReservation) {
                    var reservation = transaction.populationDomains().findByOperation(id).getFirst();
                    assertEquals(1, transaction.populationDomains().counts(reservation.bucket()).pendingDeployable());
                }
            }
            if (!removeReservation) {
                // A different animal can still reserve capacity for this owner after repair.
                var second = OperationId.create();
                assertInstanceOf(PersistenceTransactionResult.Committed.class, admissions.prepare(second,
                        new IdempotencyKey("unrelated-animal"), payload(new ProfileId(UUID.randomUUID())))
                        .completion().toCompletableFuture().join());
                admissions.claim(second).toCompletableFuture().join();
                assertEquals(OperationWorkflowResult.Status.LIVE_UNKNOWN,
                        admissions.containExpiredClaim(second).toCompletableFuture().join().status());
                assertInstanceOf(PersistenceTransactionResult.RolledBack.class, admissions.prepare(OperationId.create(),
                        new IdempotencyKey("capacity-exhausted"), payload(new ProfileId(UUID.randomUUID())))
                        .completion().toCompletableFuture().join());
                assertInstanceOf(PersistenceTransactionResult.RolledBack.class, admissions.prepare(OperationId.create(),
                        new IdempotencyKey("protected-animal"), payload)
                        .completion().toCompletableFuture().join());
            }
        } finally {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    private PopulationDomainAdmissionOperation.Payload payload(ProfileId profile) {
        return new PopulationDomainAdmissionOperation.Payload(UUID.randomUUID(), profile, OWNER,
                null, "world", null, null, null, LifecycleState.ACTIVE, "sheep", "provider",
                1, "generation", 0, 1, 1000, 1,
                List.of(new PopulationDomainAdmissionOperation.DomainInput("deployable", PopulationDomainScope.GLOBAL,
                        null, 0, 1, 1, 0, 2, 0)), List.of(), 1);
    }
}
