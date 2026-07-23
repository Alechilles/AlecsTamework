package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.persistence.facade.ReplacementNpcProfilesApi;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntime;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Released v4 profile facade parity against an imported public save. */
class ReplacementNpcProfilesApiTest {
    private static final String CAPTURED =
            "20000000-0000-0000-0000-000000000002";
    private static final String DEAD =
            "20000000-0000-0000-0000-000000000003";
    private static final String LOST =
            "20000000-0000-0000-0000-000000000004";
    private static final UUID CURRENT_ALIAS =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID HISTORICAL_ALIAS =
            UUID.fromString("00000000-0000-0000-0000-000000000011");

    @TempDir
    Path tempDir;

    @Test
    void readsReleasedProfileAndSnapshotFieldsFromReplacementAuthorities()
            throws Exception {
        Path source = tempDir.resolve("tamework.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql",
                source
        );
        PublicPersistenceRuntime runtime = runtime();
        try {
            assertTrue(runtime.start().toCompletableFuture().join().complete());
            ReplacementNpcProfilesApi api = new ReplacementNpcProfilesApi(
                    runtime.queries(),
                    Duration.ofSeconds(5)
            );

            NpcProfileView view = api.getByProfileId(CAPTURED).orElseThrow();

            assertEquals(CAPTURED, view.profileId());
            assertEquals(CURRENT_ALIAS, view.currentNpcUuid());
            assertEquals(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    view.ownerUuid()
            );
            assertEquals("Tamework_Captured", view.roleId());
            assertEquals("Captured", view.displayName());
            assertEquals(
                    Set.of("30000000-0000-0000-0000-000000000002"),
                    view.toolIds()
            );
            assertEquals(Set.of("capture"), view.activeSnapshotTypes());
            assertTrue(api.getActiveSnapshot(CAPTURED, "capture")
                    .orElseThrow().contains("\"capturedAtMs\":250"));
            assertEquals(Set.of("capture"), api.listActiveSnapshotTypes(CAPTURED));
            assertEquals(Set.of("death"), api.listActiveSnapshotTypes(DEAD));
            assertTrue(api.getActiveSnapshot(DEAD, "death")
                    .orElseThrow().contains("\"diedAtMs\":260"));
            assertEquals(Set.of("lost"), api.listActiveSnapshotTypes(LOST));
            assertTrue(api.getActiveSnapshot(LOST, "lost")
                    .orElseThrow().contains("\"lostAtMs\":270"));
            assertEquals(
                    CAPTURED,
                    api.resolveProfileId(CURRENT_ALIAS).orElseThrow()
            );
            assertEquals(
                    "20000000-0000-0000-0000-000000000001",
                    api.resolveProfileId(HISTORICAL_ALIAS).orElseThrow()
            );
            assertTrue(api.getByNpcUuid(CURRENT_ALIAS).isPresent());
            NpcProfileView cooped = api.getByProfileId(
                    "20000000-0000-0000-0000-000000000005"
            ).orElseThrow();
            assertEquals("fixture-coop", cooped.coopId());
            assertEquals(0, cooped.coopSlot());
            assertTrue(api.getByProfileId("not-a-uuid").isEmpty());
        } finally {
            runtime.close();
        }
    }

    private PublicPersistenceRuntime runtime() {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "profile-facade-test",
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed").completed(),
                        event -> {
                        },
                        boundaries(),
                        PublicPersistenceWorldReconciliation
                                .alreadyComplete(),
                        Duration.ofSeconds(5)
                )
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (rotation, operation) ->
                        CompanionAliasLiveBoundary.Result.confirmed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("capture").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("restoration").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("coop_capture").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("coop_release").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("timed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("provisioning").completed(),
                com.alechilles.alecstamework.companion.revival
                        .PaidRevivalBoundaries.unavailable()
        );
    }
}
