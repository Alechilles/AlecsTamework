package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataDecoder;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDecodeResult;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionProfileReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProfileExtensionDataStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public v4 logical-read parity through replacement profile and extension adapters. */
class PublicV4ProfileReadParityTest {
    private static final ProfileId ACTIVE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId EXTENSION =
            ProfileId.parse("20000000-0000-0000-0000-000000000006");

    @TempDir
    Path tempDir;

    @Test
    void importedPublicFixtureReadsThroughFocusedReplacementAuthorities() throws Exception {
        Path source = tempDir.resolve("tamework.sqlite");
        Path target = tempDir.resolve("tamework-state.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql",
                source
        );
        assertInstanceOf(
                PublicImportResult.Imported.class,
                new PublicPersistenceImporter(() -> -7_000).importSource(source, target)
        );
        assertTrue(Files.isRegularFile(target));

        SqliteConnectionFactory connections = new SqliteConnectionFactory(target);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        try {
            SqliteCompanionProfileReader profiles = new SqliteCompanionProfileReader(reads);
            CompanionProfileReadModel active = found(
                    profiles.findByProfile(ACTIVE).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
            );
            assertEquals("Active Ω", active.identity().displayName());
            assertEquals("Tamework_Active", active.identity().roleId());
            assertEquals(LifecycleState.UNRESOLVED, active.lifecycle().state());
            assertEquals(
                    NpcAlias.parse("00000000-0000-0000-0000-000000000001"),
                    active.currentAlias().alias()
            );
            assertTrue(active.toolLinks().isEmpty());
            assertTrue(active.currentSnapshots().isEmpty());

            CompanionProfileReadModel historicalAlias = found(
                    profiles.findByAlias(NpcAlias.parse(
                                    "00000000-0000-0000-0000-000000000011"
                            )).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
            );
            assertEquals(ACTIVE, historicalAlias.identity().profileId());

            try (Connection connection = connections.openReadConnection()) {
                PersistenceReadResult.Found<ProfileExtensionData> extension =
                        assertInstanceOf(
                                PersistenceReadResult.Found.class,
                                new SqliteProfileExtensionDataStore(connection).find(
                                        new ProfileExtensionKey(
                                                EXTENSION,
                                                "fixture",
                                                "unicode"
                                        )
                                )
                        );
                ProfileExtensionDecodeResult.Decoded decoded = assertInstanceOf(
                        ProfileExtensionDecodeResult.Decoded.class,
                        ProfileExtensionDataDecoder.decode(extension.value())
                );
                assertTrue(decoded.jsonPayload().contains("Ω"));
                assertTrue(decoded.jsonPayload().contains("-3000"));
                assertEquals(1, extension.value().revision());
            }
        } finally {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private CompanionProfileReadModel found(
            PersistenceReadResult<CompanionProfileReadModel> result
    ) {
        PersistenceReadResult.Found<CompanionProfileReadModel> found = assertInstanceOf(
                PersistenceReadResult.Found.class,
                result
        );
        return found.value();
    }
}
