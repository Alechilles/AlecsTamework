package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityStatus;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that exact scoped denial fences survive a complete runtime restart. */
class PersistenceScopeDurabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void activeProfileQuarantineReloadsBeforeAvailabilityOpens() throws Exception {
        Path database = tempDir.resolve("scope-durability.sqlite");
        PersistenceScope affected = scope("profile-a");

        try (RuntimeHarness first = RuntimeHarness.open(database)) {
            PersistenceIncidentReporter.ReportSubmission submission = first.runtime.reporter().report(
                    failure(affected));
            assertTrue(submission.durableCompletion().get(5, TimeUnit.SECONDS));
            assertTrue(first.queue.awaitIdle(5_000L));
        }

        try (RuntimeHarness restarted = RuntimeHarness.open(database)) {
            assertEquals(1, restarted.runtime.quarantines().size());
            assertEquals(PersistenceMutationAvailabilityStatus.QUARANTINED,
                    restarted.runtime.availability().decide(context(affected)).status());
            assertTrue(restarted.runtime.availability().decide(context(scope("profile-b"))).allowed(),
                    "a durable profile fence must not deny an unrelated profile after restart");
        }
    }

    private PersistenceFailureContext failure(PersistenceScope scope) {
        return new PersistenceFailureContext(
                "publication_failed", PersistenceDomain.OWNER_MUTATION,
                PersistenceOperationPhase.PUBLICATION, PersistenceTransactionOutcome.COMMITTED,
                List.of(scope), true, true, false, false,
                false, false, false, true, "operation-profile-a",
                new IllegalStateException("publication failed"));
    }

    private PersistenceMutationContext context(PersistenceScope scope) {
        return new PersistenceMutationContext(
                PersistenceDomain.OWNER_MUTATION, "owner-update", List.of(scope), Set.of(),
                PersistenceMutationDelta.ZERO, "trace", "operation", true, true);
    }

    private PersistenceScope scope(String profileId) {
        return new PersistenceScope(
                PersistenceScopeType.PROFILE, profileId, "safe-hash-" + profileId,
                "canonical_profile_catalog");
    }

    private static final class RuntimeHarness implements AutoCloseable {
        private final PersistenceWriteQueue queue;
        private final PersistenceResilienceRuntime runtime;

        private RuntimeHarness(PersistenceWriteQueue queue, PersistenceResilienceRuntime runtime) {
            this.queue = queue;
            this.runtime = runtime;
        }

        private static RuntimeHarness open(Path database) throws Exception {
            SqliteConnectionManager connections = new SqliteConnectionManager(database);
            try (Connection connection = connections.openConnection()) {
                new SqliteSchemaMigrator().migrate(connection);
            }
            PersistenceWriteQueue queue = new PersistenceWriteQueue(
                    connections, new PersistenceHealthService(), null);
            PersistenceResilienceRuntime runtime = PersistenceResilienceRuntime.initialize(
                    "boot-test", connections, queue, new PersistenceStorageHealthService(), null);
            return new RuntimeHarness(queue, runtime);
        }

        @Override
        public void close() {
            runtime.close();
            queue.close();
        }
    }
}
