package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataEntryView;
import com.alechilles.alecstamework.api.ProfileDataOperationStatus;
import com.alechilles.alecstamework.api.ProfileDataOperationView;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteDatabaseOperationCoordinator;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEvidenceReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProfileExtensionOperations;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProfileExtensionReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionGateway;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end released profile-data contract against only replacement authorities. */
class ReplacementProfileDataApiTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private ReplacementProfileDataApi api;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(tempDir.resolve("state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqlitePersistenceTransactionContext(connection)
                    .identities()
                    .createProfile(new CompanionIdentity(
                            PROFILE,
                            "Companion",
                            "role",
                            null,
                            null,
                            "world",
                            -10_000,
                            -10_000,
                            -10_000,
                            0
                    ));
            connection.commit();
        }
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(ProfileExtensionMutationDefinition.INSTANCE)
                ),
                units
        );
        AtomicLong clock = new AtomicLong(-9_000);
        SqliteProfileExtensionOperations operations =
                new SqliteProfileExtensionOperations(
                        new SqliteDatabaseOperationCoordinator(
                                engine,
                                new SqliteOperationEvidenceReader(reads),
                                new ProjectionCoordinator(
                                        new SqliteProjectionGateway(reads, units),
                                        ProjectionRetryPolicy.DEFAULT,
                                        clock::incrementAndGet
                                ),
                                clock::incrementAndGet
                        ),
                        List.of()
                );
        api = new ReplacementProfileDataApi(
                new SqliteProfileExtensionReader(reads),
                operations,
                new SqliteOperationReader(reads),
                clock::incrementAndGet,
                Duration.ofSeconds(5)
        );
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown(Duration.ofSeconds(5));
        }
        if (reads != null) {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void compatibilityReadsWritesListsAndDeletesUseReplacementStore() throws Exception {
        assertTrue(api.put(
                PROFILE.toString(),
                "Example:Mod",
                "settings",
                "{\"enabled\":true}"
        ));
        awaitValue("settings", true);

        assertEquals(
                Map.of("settings", "{\"enabled\":true}"),
                api.list(PROFILE.toString(), "Example:Mod")
        );
        ProfileDataEntryView entry = api.getVersioned(
                PROFILE.toString(),
                "Example:Mod",
                "settings"
        ).orElseThrow();
        assertEquals(1, entry.revision());
        assertTrue(entry.updatedAtMs() < 0);

        assertTrue(api.delete(
                PROFILE.toString(),
                "Example:Mod",
                "settings"
        ));
        awaitValue("settings", false);
        assertEquals(Map.of(), api.list(PROFILE.toString(), "Example:Mod"));
    }

    @Test
    void compareAndSetReplaysAndPublishesDurableDenials() throws Exception {
        ProfileDataCompareAndSetRequest request =
                new ProfileDataCompareAndSetRequest(
                        PROFILE.toString(),
                        "Example:Mod",
                        "counter",
                        0,
                        "create-counter",
                        "{\"value\":1}"
                );

        ProfileDataCompareAndSetResult committed =
                api.compareAndSet(request).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        ProfileDataCompareAndSetResult replay =
                api.compareAndSet(request).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        ProfileDataCompareAndSetResult stale = api.compareAndSet(
                new ProfileDataCompareAndSetRequest(
                        PROFILE.toString(),
                        "Example:Mod",
                        "counter",
                        0,
                        "stale-counter",
                        "{\"value\":2}"
                )
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        Optional<ProfileDataOperationView> found = api.findOperation(
                "Example:Mod",
                "create-counter"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(ProfileDataCompareAndSetResult.Status.COMMITTED,
                committed.status());
        assertEquals(committed, replay);
        assertEquals(1, committed.committedEntry().orElseThrow().revision());
        assertEquals(ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED,
                stale.status());
        assertEquals("profile-data-revision-mismatch", stale.reason());
        assertEquals(
                committed.durableOperation().orElseThrow().operationId(),
                found.orElseThrow().operationId()
        );
        assertEquals(ProfileDataOperationStatus.COMMITTED,
                found.orElseThrow().status());
        assertTrue(found.orElseThrow().updatedAtMs() < 0);
    }

    @Test
    void reservedNamespaceAndInvalidProfilesFailWithoutAdmission() throws Exception {
        assertFalse(api.put(
                PROFILE.toString(),
                "Alechilles:Tamework",
                "key",
                "{}"
        ));
        assertFalse(api.delete("not-a-uuid", "Example:Mod", "key"));
        assertEquals(
                ProfileDataCompareAndSetResult.Status.UNAVAILABLE,
                api.compareAndSet(new ProfileDataCompareAndSetRequest(
                        PROFILE.toString(),
                        "Alechilles:Tamework",
                        "key",
                        0,
                        "reserved",
                        "{}"
                )).toCompletableFuture().get(10, TimeUnit.SECONDS).status()
        );
        assertEquals(
                Optional.empty(),
                api.findOperation("Alechilles:Tamework", "reserved")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
    }

    private void awaitValue(String key, boolean present) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (api.get(PROFILE.toString(), "Example:Mod", key).isPresent()
                    == present) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Profile data did not reach expected state");
    }
}
