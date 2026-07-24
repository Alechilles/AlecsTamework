package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registry-driven composition checks for the complete public SQLite adapter. */
class SqlitePublicPersistenceAdapterTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqlitePersistenceKernel kernel;
    private SqliteConnectionFactory connections;

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void composesAllOperationsAndExactRegistryConsumers() {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -100).initialize();
        kernel = new SqlitePersistenceKernel(connections);
        SqlitePublicPersistenceAdapter adapter =
                new SqlitePublicPersistenceAdapter(
                        PublicPersistenceFeatureRegistry.create(),
                        kernel,
                        PersistenceOperationAdmissionGate.allowAll(),
                        () -> -100,
                        (claim, operation) ->
                                LiveOperationResult.confirmed(
                                        "test_refund"
                                ).completed(),
                        event -> {
                        }
                );

        assertNotNull(adapter.profileOperations());
        assertNotNull(adapter.aliasOperations());
        assertNotNull(adapter.captureOperations());
        assertNotNull(adapter.dormantOperations());
        assertNotNull(adapter.restorationOperations());
        assertNotNull(adapter.coopSlotOperations());
        assertNotNull(adapter.coopCaptureOperations());
        assertNotNull(adapter.coopReleaseOperations());
        assertNotNull(adapter.extensionOperations());
        assertNotNull(adapter.profileReader());
        assertNotNull(adapter.lifecycleReader());
        assertNotNull(adapter.coopReader());
        assertNotNull(adapter.extensionReader());
        assertNotNull(adapter.coopIndex());
        assertNotNull(adapter.extensionIndex());
        assertNotSame(
                adapter.publicOperations().engine(),
                adapter.recoveryOperations().engine()
        );
        assertEquals(
                1,
                adapter.projections().requiredFor(
                        CompanionProfileMutationDefinition.INSTANCE.kind()
                ).size()
        );
        assertEquals(
                2,
                adapter.projections().requiredFor(
                        CompanionCoopCaptureDefinition.INSTANCE.kind()
                ).size()
        );
        assertEquals(
                1,
                adapter.projections().requiredFor(
                        ProfileExtensionMutationDefinition.INSTANCE.kind()
                ).size()
        );
    }

    @Test
    void rebuildsCanonicalProjectionBeforeCatchingUpEveryConsumer()
            throws Exception {
        SqlitePublicPersistenceAdapter adapter = adapter();

        SqlitePublicProjectionStartupResult result =
                adapter.buildProjections().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicProjectionStartupResult.Status.COMPLETE,
                result.status()
        );
        assertEquals(3, result.catchUps().size());
        assertEquals(0, adapter.coopIndex().snapshot().size());
    }

    @Test
    void resumesPreparedProfileThroughTheSameTypedAdapter() throws Exception {
        SqlitePublicPersistenceAdapter adapter = adapter();
        prepareProfile(adapter);

        SqlitePublicRecoveryResult result = adapter.recover(
                boundaries(),
                "startup-worker"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, result.status());
        assertEquals(1, result.completedCount());
        assertEquals(0, result.deferredCount());
        assertEquals(List.of(), result.quarantinedScopes());
        PersistenceReadResult.Found<?> found = assertInstanceOf(
                PersistenceReadResult.Found.class,
                adapter.profileReader().findByProfile(PROFILE)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
        assertNotNull(found.value());
    }

    @Test
    void containsUnknownClaimWithoutDispatchingIt() throws Exception {
        SqlitePublicPersistenceAdapter adapter = adapter();
        OperationEnvelope prepared = prepareProfile(adapter);
        OperationEnvelope applying = committed(adapter.publicOperations()
                .engine().transition(
                        prepared,
                        OperationPhase.LIVE_APPLYING,
                        null,
                        null,
                        -90
                ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
        committed(adapter.publicOperations().engine().transition(
                applying,
                OperationPhase.UNKNOWN,
                "live",
                "ambiguous_test",
                -80
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO operation_participant(
                         operation_id, scope_type, scope_key
                     ) VALUES (?, 'GLOBAL', '*')
                     """)) {
            statement.setString(1, OPERATION.toString());
            statement.executeUpdate();
        }

        SqlitePublicRecoveryResult result = adapter.recover(
                boundaries(),
                "startup-worker"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, result.status());
        assertEquals(0, result.completedCount());
        assertEquals(
                List.of(
                        OperationScope.operation(OPERATION),
                        OperationScope.profile(PROFILE)
                ),
                result.quarantinedScopes()
        );
        try (Connection connection = connections.openReadConnection()) {
            assertTrue(new SqliteIncidentStore(connection)
                    .findQuarantine(OperationScope.global())
                    .isEmpty());
        }
    }

    @Test
    void startupReconciliationUsesSharedProtocolBeforePublicAdmission()
            throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("startup-reconciliation.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -100).initialize();
        kernel = new SqlitePersistenceKernel(connections);
        SqlitePublicPersistenceAdapter adapter =
                new SqlitePublicPersistenceAdapter(
                        PublicPersistenceFeatureRegistry.create(),
                        kernel,
                        (kind, feature, participants) -> {
                            throw new IllegalStateException(
                                    "public_mutation_not_ready"
                            );
                        },
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("test_refund").completed(),
                        event -> {
                        }
                );
        NpcAlias alias = NpcAlias.parse(
                "30000000-0000-0000-0000-000000000001"
        );
        seedUnresolvedProfile(alias);
        assertEquals(
                SqlitePublicProjectionStartupResult.Status.COMPLETE,
                adapter.buildProjections().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS).status()
        );
        CompanionProfileMutation.ReconcileLoaded reconciliation =
                new CompanionProfileMutation.ReconcileLoaded(
                        PROFILE,
                        LifecycleRevision.INITIAL,
                        ReconciliationGeneration.INITIAL,
                        alias,
                        alias,
                        "loaded-world",
                        -90
                );

        assertThrows(
                IllegalStateException.class,
                () -> adapter.profileOperations().submit(
                        OperationId.create(),
                        new IdempotencyKey("public-reconciliation"),
                        reconciliation
                )
        );
        var submitted = adapter.reconcileProfileAtStartup(
                OperationId.create(),
                new IdempotencyKey("startup-reconciliation"),
                reconciliation
        );
        OperationWorkflowResult result = submitted.completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        try (Connection connection = connections.openReadConnection()) {
            CompanionLifecycle lifecycle =
                    new SqliteCompanionLifecycleStore(connection)
                            .findByProfile(PROFILE)
                            .orElseThrow();
            assertEquals(LifecycleState.ACTIVE, lifecycle.state());
            assertEquals(
                    LifecycleLocation.liveEntity(
                            alias.toString(),
                            "loaded-world"
                    ),
                    lifecycle.location()
            );
        }
    }

    private SqlitePublicPersistenceAdapter adapter() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("projection-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -100).initialize();
        kernel = new SqlitePersistenceKernel(connections);
        return new SqlitePublicPersistenceAdapter(
                PublicPersistenceFeatureRegistry.create(),
                kernel,
                PersistenceOperationAdmissionGate.allowAll(),
                () -> -100,
                (claim, operation) ->
                        LiveOperationResult.confirmed(
                                "test_refund"
                        ).completed(),
                event -> {
                }
        );
    }

    private OperationEnvelope prepareProfile(
            SqlitePublicPersistenceAdapter adapter
    ) throws Exception {
        CompanionProfileMutation mutation = new CompanionProfileMutation.Create(
                identity(),
                new CompanionLifecycle(
                        PROFILE,
                        OwnerId.parse(
                                "10000000-0000-0000-0000-000000000001"
                        ),
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        -100,
                        ReconciliationGeneration.INITIAL,
                        null
                ),
                List.of(),
                -100
        );
        OperationRequest<CompanionProfileMutation> request =
                new OperationRequest<>(
                        OPERATION,
                        new IdempotencyKey("recover-profile"),
                        mutation,
                        SqliteCompanionProfileOperations.FEATURE_SCOPE,
                        null,
                        List.of(OperationScope.profile(PROFILE)),
                        -100
                );
        return committed(adapter.publicOperations().engine().prepare(
                CompanionProfileMutationDefinition.INSTANCE,
                request
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    private CompanionIdentity identity() {
        String metadata = "{\"source\":\"recovery-test\"}";
        return new CompanionIdentity(
                PROFILE,
                "Companion",
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -100,
                -100,
                -100,
                0
        );
    }

    private void seedUnresolvedProfile(NpcAlias alias) throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            assertTrue(new SqliteCompanionIdentityStore(connection)
                    .createProfile(identity()).applied());
            assertTrue(new SqliteCompanionLifecycleStore(connection)
                    .create(new CompanionLifecycle(
                            PROFILE,
                            OwnerId.parse(
                                    "10000000-0000-0000-0000-000000000001"
                            ),
                            LifecycleState.UNRESOLVED,
                            LifecycleLocation.unresolved(),
                            LifecycleRevision.INITIAL,
                            null,
                            -100,
                            ReconciliationGeneration.INITIAL,
                            null,
                            "owner-world"
                    )).applied());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO companion_alias(
                        npc_uuid, profile_id, alias_generation, alias_state,
                        lease_operation_id, mapped_at_ms, retired_at_ms
                    ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                    """)) {
                statement.setString(1, alias.toString());
                statement.setString(2, PROFILE.toString());
                statement.setLong(3, -100);
                statement.executeUpdate();
            }
        }
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        LiveOperationResult.confirmed("capture").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("capture_release")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("restoration").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_capture").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_release").completed()
        );
    }

    @SuppressWarnings("unchecked")
    private OperationEnvelope committed(
            PersistenceTransactionResult<OperationEnvelope> result
    ) {
        return ((PersistenceTransactionResult.Committed<OperationEnvelope>)
                assertInstanceOf(
                        PersistenceTransactionResult.Committed.class,
                        result
                )).value();
    }
}
