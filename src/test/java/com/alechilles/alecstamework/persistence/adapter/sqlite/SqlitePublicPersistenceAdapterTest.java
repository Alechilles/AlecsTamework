package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
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
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
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
import java.util.UUID;
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
    private static final ProfileId DOMAIN_PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId DOMAIN_OWNER =
            OwnerId.parse("10000000-0000-0000-0000-000000000002");
    private static final OperationId DOMAIN_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000002");
    private static final OperationId BLOCKED_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000005");
    private static final OperationId DORMANT_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000006");
    private static final OperationId OWNER_RELEASE_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000007");
    private static final NpcAlias DOMAIN_ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000002");
    private static final OwnerId MISMATCH_OWNER =
            OwnerId.parse("10000000-0000-0000-0000-000000000003");
    private final java.util.concurrent.atomic.AtomicLong domainClock =
            new java.util.concurrent.atomic.AtomicLong(-100);

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
        assertNotNull(adapter.ownerPopulationOperations());
        assertNotNull(adapter.ownerPopulationReconciliationOperations());
        assertNotNull(adapter.populationGroupOperations());
        assertNotNull(adapter.commandRosterOperations());
        assertNotNull(adapter.commandRosterTransitionOperations());
        assertNotNull(adapter.timedSummonOperations());
        assertNotNull(adapter.timedSummonTransitionOperations());
        assertNotNull(adapter.provisioningOperations());
        assertNotNull(adapter.provisioningActivationOperations());
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
        assertNotNull(adapter.populationGroupReader());
        assertNotNull(adapter.commandRosterReader());
        assertNotNull(adapter.timedSummonReader());
        assertNotNull(adapter.provisioningReader());
        assertNotNull(adapter.coopIndex());
        assertNotNull(adapter.ownerPopulationIndex());
        assertNotNull(adapter.populationGroupIndex());
        assertNotNull(adapter.commandRosterIndex());
        assertNotNull(adapter.timedSummonIndex());
        assertNotNull(adapter.provisioningIndex());
        assertNotNull(adapter.extensionIndex());
        assertNotSame(
                adapter.publicOperations().engine(),
                adapter.recoveryOperations().engine()
        );
        assertEquals(
                5,
                adapter.projections().requiredFor(
                        CompanionProfileMutationDefinition.INSTANCE.kind()
                ).size()
        );
        assertEquals(
                6,
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
        assertEquals(
                6,
                adapter.projections().requiredFor(
                        CommandRosterMembershipDefinition.INSTANCE.kind()
                ).size()
        );
        assertEquals(
                6,
                adapter.projections().requiredFor(
                        TimedSummonLeaseMutationDefinition.INSTANCE.kind()
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
        assertEquals(10, result.catchUps().size());
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
    void completesStartupAfterContainingLiveUnknownDomainAdmission()
            throws Exception {
        SqlitePublicPersistenceAdapter adapter = populationDomainAdapter();
        committed(adapter.populationDomainAdmissionOperations().prepare(
                DOMAIN_OPERATION,
                new IdempotencyKey("recover-domain-live-unknown"),
                domainPayload()
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(
                OperationPhase.LIVE_APPLYING,
                adapter.populationDomainAdmissionOperations()
                        .claim(DOMAIN_OPERATION).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS).phase()
        );
        prepareProfile(adapter);
        OperationEnvelope blockedPrepared = prepareProfile(
                adapter,
                BLOCKED_OPERATION,
                DOMAIN_PROFILE,
                DOMAIN_OWNER,
                -90
        );
        OperationEnvelope blockedApplying = committed(adapter.publicOperations().engine().transition(
                blockedPrepared, OperationPhase.LIVE_APPLYING, null, null, -89)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
        committed(adapter.publicOperations().engine().transition(
                blockedApplying, OperationPhase.UNKNOWN, "live", "ambiguous_test", -88)
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS));

        SqlitePublicRecoveryResult result = adapter.recover(
                boundaries(),
                "startup-worker"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, result.status());
        assertEquals(1, result.completedCount());
        assertEquals(2, result.deferredCount());
        PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel>
                domain = assertInstanceOf(
                PersistenceReadResult.Found.class,
                adapter.operationReader().find(DOMAIN_OPERATION)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
        assertEquals(OperationPhase.UNKNOWN, domain.value().operation().phase());
        assertActiveQuarantine(adapter, OperationScope.operation(DOMAIN_OPERATION));
        assertActiveQuarantine(adapter, OperationScope.profile(DOMAIN_PROFILE));
        assertActiveQuarantine(adapter, OperationScope.owner(DOMAIN_OWNER));
        PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel>
                blocked = assertInstanceOf(
                PersistenceReadResult.Found.class,
                adapter.operationReader().find(BLOCKED_OPERATION)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
        assertEquals(
                OperationPhase.UNKNOWN,
                blocked.value().operation().phase()
        );
        assertInstanceOf(
                PersistenceReadResult.Found.class,
                adapter.profileReader().findByProfile(PROFILE)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
        // A fresh recovery run must also respect fences created in the previous run.
        // Otherwise the overlapping claim attempts another incident and fails startup.
        domainClock.set(60_000); // First-run recovery leases have expired.
        SqlitePublicRecoveryResult restarted = adapter.recover(boundaries(), "restart-worker")
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, restarted.status());
        assertEquals(0, restarted.completedCount());
        assertActiveQuarantine(adapter, OperationScope.owner(DOMAIN_OWNER));
    }

    @Test
    void defersPendingPopulationDomainOwnerReleaseWithoutBlockingRecovery()
            throws Exception {
        SqlitePublicPersistenceAdapter adapter = populationDomainAdapter();
        seedActiveDomainProfile();
        preparePendingDomainAdmission(adapter);

        OperationWorkflowResult initial = adapter.ownerPopulationOperations().submit(
                OWNER_RELEASE_OPERATION,
                new IdempotencyKey("recover-owner-pending-domain"),
                new OwnerPopulationTransitionRequest(
                        DOMAIN_PROFILE,
                        LifecycleRevision.INITIAL,
                        DOMAIN_OWNER,
                        "world",
                        null,
                        null,
                        0,
                        0,
                        -200
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED, initial.status());
        prepareProfile(adapter);

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "startup-worker"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, recovered.status());
        assertEquals(OperationPhase.PREPARED, operationPhase(adapter, OWNER_RELEASE_OPERATION));
        assertInstanceOf(PersistenceReadResult.Found.class, adapter.profileReader()
                .findByProfile(PROFILE).toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void defersPendingPopulationDomainDormantWithoutBlockingRecovery()
            throws Exception {
        SqlitePublicPersistenceAdapter adapter = populationDomainAdapter();
        seedActiveDomainProfile();
        preparePendingDomainAdmission(adapter);

        String snapshotJson = "{\"health\":0}";
        OperationWorkflowResult initial = adapter.dormantOperations().submit(
                DORMANT_OPERATION,
                new IdempotencyKey("recover-dormant-pending-domain"),
                new CompanionDormantTransitionRequest(
                        DOMAIN_PROFILE,
                        LifecycleRevision.INITIAL,
                        new CompanionSnapshot(
                                SnapshotId.parse("50000000-0000-0000-0000-000000000001"),
                                DOMAIN_PROFILE,
                                DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind(),
                                1,
                                snapshotJson,
                                Sha256Hash.ofUtf8(snapshotJson),
                                LifecycleRevision.INITIAL,
                                true,
                                -200
                        ),
                        new DormantSourceEvidence(
                                DOMAIN_ALIAS,
                                "world",
                                DormantSourceEvidence.Kind.DEATH_COMPONENT,
                                ReconciliationGeneration.INITIAL,
                                "recover-dormant-pending-domain",
                                -200
                        ),
                        -200
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED, initial.status());
        prepareProfile(adapter);

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "startup-worker"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE, recovered.status());
        assertEquals(OperationPhase.PREPARED, operationPhase(adapter, DORMANT_OPERATION));
        assertInstanceOf(PersistenceReadResult.Found.class, adapter.profileReader()
                .findByProfile(PROFILE).toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void doesNotDeferOwnerPopulationSourceMismatch() throws Exception {
        SqlitePublicPersistenceAdapter adapter = populationDomainAdapter();
        seedActiveDomainProfile();
        OwnerPopulationTransitionRequest release =
                new OwnerPopulationTransitionRequest(
                        DOMAIN_PROFILE,
                        LifecycleRevision.INITIAL,
                        MISMATCH_OWNER,
                        "world",
                        null,
                        null,
                        0,
                        0,
                        -200
                );
        committed(adapter.publicOperations().engine().prepare(
                OwnerPopulationTransitionDefinition.INSTANCE,
                new OperationRequest<>(
                        OWNER_RELEASE_OPERATION,
                        new IdempotencyKey("recover-owner-source-mismatch"),
                        release,
                        SqliteOwnerPopulationTransitionOperations.FEATURE_SCOPE,
                        LifecycleRevision.INITIAL,
                        List.of(
                                OperationScope.profile(DOMAIN_PROFILE),
                                OperationScope.owner(MISMATCH_OWNER)
                        ),
                        -200
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "startup-worker"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.DISPATCH_FAILED,
                recovered.status()
        );
        assertEquals("owner_population_source_mismatch", recovered.failure().getMessage());
        assertEquals(OperationPhase.PREPARED, operationPhase(adapter, OWNER_RELEASE_OPERATION));
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

    private SqlitePublicPersistenceAdapter populationDomainAdapter() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("population-domain-state.sqlite")
        );
        assertInstanceOf(
                PersistenceTransactionResult.Committed.class,
                new SqliteSchemaV2Manager(connections, () -> -100).initialize()
        );
        kernel = new SqlitePersistenceKernel(connections);
        return new SqlitePublicPersistenceAdapter(
                PublicPersistenceFeatureRegistry.create(),
                kernel,
                PersistenceOperationAdmissionGate.allowAll(),
                domainClock::get,
                (claim, operation) -> LiveOperationResult.confirmed(
                        "test_refund"
                ).completed(),
                event -> {
                }
        );
    }

    private OperationEnvelope prepareProfile(
            SqlitePublicPersistenceAdapter adapter
    ) throws Exception {
        return prepareProfile(
                adapter,
                OPERATION,
                PROFILE,
                OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                -100
        );
    }

    private OperationEnvelope prepareProfile(
            SqlitePublicPersistenceAdapter adapter,
            OperationId operationId,
            ProfileId profileId,
            OwnerId ownerId,
            long createdAtMs
    ) throws Exception {
        CompanionProfileMutation mutation = new CompanionProfileMutation.Create(
                identity(profileId),
                new CompanionLifecycle(
                        profileId,
                        ownerId,
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        createdAtMs,
                        ReconciliationGeneration.INITIAL,
                        null
                ),
                List.of(),
                createdAtMs
        );
        OperationRequest<CompanionProfileMutation> request =
                new OperationRequest<>(
                        operationId,
                        new IdempotencyKey("recover-profile-" + operationId),
                        mutation,
                        SqliteCompanionProfileOperations.FEATURE_SCOPE,
                        null,
                        List.of(OperationScope.profile(profileId)),
                        createdAtMs
                );
        return committed(adapter.publicOperations().engine().prepare(
                CompanionProfileMutationDefinition.INSTANCE,
                request
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    private PopulationDomainAdmissionOperation.Payload domainPayload() {
        return domainPayload(List.of());
    }

    private PopulationDomainAdmissionOperation.Payload domainPayload(
            List<PopulationDomainAdmissionOperation.DomainInput> domains
    ) {
        return new PopulationDomainAdmissionOperation.Payload(
                UUID.fromString("40000000-0000-0000-0000-000000000003"),
                DOMAIN_PROFILE,
                DOMAIN_OWNER,
                null,
                null,
                null,
                null,
                null,
                LifecycleState.ACTIVE,
                "recovery-domain-group",
                "recovery-domain-provider",
                1,
                "recovery-generation",
                1,
                1,
                -50,
                1,
                domains,
                List.of(),
                -100
        );
    }

    private void preparePendingDomainAdmission(
            SqlitePublicPersistenceAdapter adapter
    ) throws Exception {
        committed(adapter.populationDomainAdmissionOperations().prepare(
                DOMAIN_OPERATION,
                new IdempotencyKey("recover-pending-domain"),
                domainPayload(List.of(new PopulationDomainAdmissionOperation.DomainInput(
                        "recovery-domain",
                        PopulationDomainScope.GLOBAL,
                        null,
                        1,
                        1,
                        1,
                        12,
                        6,
                        1
                )))
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(OperationPhase.LIVE_APPLYING,
                adapter.populationDomainAdmissionOperations().claim(DOMAIN_OPERATION)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS).phase());
    }

    private void seedActiveDomainProfile() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.identities().createProfile(identity(DOMAIN_PROFILE)).applied());
            assertTrue(transaction.lifecycles().create(new CompanionLifecycle(
                    DOMAIN_PROFILE,
                    DOMAIN_OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(DOMAIN_ALIAS.toString(), "world"),
                    LifecycleRevision.INITIAL,
                    null,
                    -300,
                    ReconciliationGeneration.INITIAL,
                    null,
                    "world"
            )).applied());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO companion_alias(
                        npc_uuid, profile_id, alias_generation, alias_state,
                        lease_operation_id, mapped_at_ms, retired_at_ms
                    ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                    """)) {
                statement.setString(1, DOMAIN_ALIAS.toString());
                statement.setString(2, DOMAIN_PROFILE.toString());
                statement.setLong(3, -300);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private OperationPhase operationPhase(
            SqlitePublicPersistenceAdapter adapter,
            OperationId operationId
    ) throws Exception {
        PersistenceReadResult.Found<SqliteOperationReader.OperationReadModel> found =
                assertInstanceOf(PersistenceReadResult.Found.class,
                        adapter.operationReader().find(operationId).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
        return found.value().operation().phase();
    }

    private void assertActiveQuarantine(
            SqlitePublicPersistenceAdapter adapter,
            OperationScope scope
    ) throws Exception {
        assertInstanceOf(
                PersistenceReadResult.Found.class,
                adapter.containmentReader().findFirstActive(List.of(scope))
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
    }

    private CompanionIdentity identity() {
        return identity(PROFILE);
    }

    private CompanionIdentity identity(ProfileId profileId) {
        String metadata = "{\"source\":\"recovery-test\"}";
        return new CompanionIdentity(
                profileId,
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
