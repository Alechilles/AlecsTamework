package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionProfileReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.facade.ReplacementNpcProfilesApi;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Released v4 profile facade parity against an imported public save. */
class ReplacementNpcProfilesApiTest {
    private static final String CAPTURED =
            "20000000-0000-0000-0000-000000000002";
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
        Path target = tempDir.resolve("tamework-state.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql",
                source
        );
        assertInstanceOf(
                PublicImportResult.Imported.class,
                new PublicPersistenceImporter(() -> -7_000)
                        .importSource(source, target)
        );
        SqliteReadExecutor reads =
                new SqliteReadExecutor(new SqliteConnectionFactory(target));
        try {
            ReplacementNpcProfilesApi api = new ReplacementNpcProfilesApi(
                    new SqliteCompanionProfileReader(reads),
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
            assertEquals(
                    CAPTURED,
                    api.resolveProfileId(CURRENT_ALIAS).orElseThrow()
            );
            assertEquals(
                    "20000000-0000-0000-0000-000000000001",
                    api.resolveProfileId(HISTORICAL_ALIAS).orElseThrow()
            );
            assertTrue(api.getByNpcUuid(CURRENT_ALIAS).isPresent());
            assertTrue(api.getByProfileId("not-a-uuid").isEmpty());
        } finally {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }
}
