package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.PersistenceLifecycleAdmissionGateway;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end canonical state and recovery tests for initial activation. */
class SqliteProvisioningActivationOperationsTest {
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000097");
    private static final long GRANTED_AT = -5_000;
    private static final long ACTIVATED_AT = -4_000;

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqlitePersistenceKernel kernel;
    private SqlitePublicPersistenceAdapter adapter;
    private AtomicReference<PersistenceLifecycleAdmissionGateway>
            lifecycleAdmission;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("provisioning-activation.db")
        );
        new SqliteSchemaV2Manager(connections, () -> -10_000)
                .initialize();
        kernel = new SqlitePersistenceKernel(connections);
        adapter = new SqlitePublicPersistenceAdapter(
                PublicPersistenceFeatureRegistry.create(),
                kernel,
                PersistenceOperationAdmissionGate.allowAll(),
                () -> ACTIVATED_AT,
                (claim, operation) ->
                        LiveOperationResult.confirmed("refund")
                                .completed(),
                event -> {
                }
        );
        lifecycleAdmission = new AtomicReference<>(request ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        LifecycleAdmissionEvidence.neutral()
                ));
        adapter.bindLifecycleAdmission(request -> lifecycleAdmission.get()
                .authorize(request));
    }

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void activationPreservesProvenanceAndCommitsOneCanonicalPath()
            throws Exception {
        ProvisioningOrigin origin =
                new ProvisioningOrigin("test:activation", "plain");
        grant(1, origin, false, 2, 2);
        ProvisioningRecord provenance = provenance(origin);
        NpcAlias alias = alias(1);

        OperationWorkflowResult activated = activate(
                2, activation(origin, alias, null),
                LiveOperationResult.confirmed("spawn-plain")
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                activated.status()
        );
        assertEquals(3, activated.events().size());
        assertEquals(provenance, provenance(origin));
        assertEquals(LifecycleState.ACTIVE,
                lifecycle(origin).state());
        assertEquals(2, lifecycle(origin).revision().value());
        assertEquals(
                CompanionAlias.State.CURRENT,
                resolvedAlias(alias).state()
        );
        assertEquals(
                new PopulationGroupCounts(1, 1, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
        assertEquals(0, reservationCount(operationId(2)));
        assertTrue(adapter.timedSummonIndex()
                .laggingProfiles().isEmpty());
    }

    @Test
    void timedActivationCommitsItsInitialLeaseInTheSameOperation()
            throws Exception {
        ProvisioningOrigin origin =
                new ProvisioningOrigin("test:activation", "timed");
        grant(10, origin, true, 2, 2);
        NpcAlias alias = alias(10);
        TimedSummonLease lease = lease(origin);

        OperationWorkflowResult activated = activate(
                11,
                activation(
                        origin,
                        alias,
                        new TimedSummonActivation(
                                new CommandFamilyKey(OWNER, "summon"),
                                origin.commandSlotId(),
                                1,
                                lease
                        )
                ),
                LiveOperationResult.confirmed("spawn-timed")
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                activated.status()
        );
        assertEquals(4, activated.events().size());
        assertEquals(lease, timedLease(origin));
        assertTrue(adapter.timedSummonIndex()
                .laggingProfiles().isEmpty());
        assertEquals(LifecycleState.ACTIVE,
                lifecycle(origin).state());
        assertEquals(provenance(origin).origin(), origin);
    }

    @Test
    void unavailableWorldKeepsExactFencesAndRecoveryResumes()
            throws Exception {
        ProvisioningOrigin origin =
                new ProvisioningOrigin("test:activation", "late-world");
        grant(20, origin, false, 2, 2);
        NpcAlias alias = alias(20);
        ProvisioningActivationRequest request =
                activation(origin, alias, null);

        OperationWorkflowResult retryable = activate(
                21,
                request,
                LiveOperationResult.retryable(
                        "world_not_loaded", null
                )
        );

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                retryable.status()
        );
        assertEquals(1, lifecycle(origin).revision().value());
        assertEquals(
                CompanionAlias.State.LEASED,
                resolvedAlias(alias).state()
        );
        assertEquals(1, reservationCount(operationId(21)));

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "provisioning-activation-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(1, recovered.completedCount());
        assertEquals(2, lifecycle(origin).revision().value());
        assertEquals(
                CompanionAlias.State.CURRENT,
                resolvedAlias(alias).state()
        );
        assertEquals(0, reservationCount(operationId(21)));
    }

    @Test
    void canonicalRoleMismatchFailsBeforeLiveProjection()
            throws Exception {
        ProvisioningOrigin origin =
                new ProvisioningOrigin("test:activation", "wrong-role");
        grant(30, origin, false, 2, 2);
        ProvisioningActivationRequest exact =
                activation(origin, alias(30), null);
        ProvisioningActivationRequest mismatched =
                new ProvisioningActivationRequest(
                        exact.origin(),
                        exact.groupAdmission(),
                        exact.targetAlias(),
                        "Other",
                        exact.fullState(),
                        exact.placement(),
                        exact.spawnReceiptKey(),
                        exact.timedActivation(),
                        exact.requestedAtMs()
                );
        AtomicBoolean invoked = new AtomicBoolean();

        OperationWorkflowResult result =
                adapter.provisioningActivationOperations().submit(
                        operationId(31),
                        mismatched,
                        (request, operation) -> {
                            invoked.set(true);
                            return LiveOperationResult.confirmed(
                                    "must-not-run"
                            ).completed();
                        }
                ).completion().toCompletableFuture().get(
                        10, TimeUnit.SECONDS
                );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(false, invoked.get());
        assertEquals(
                LifecycleState.PROVISIONED_DORMANT,
                lifecycle(origin).state()
        );
    }

    @Test
    void managedActivationFreezesAdmissionBeforeLiveAndSkipsProviderOnReplay()
            throws Exception {
        ProvisioningOrigin origin =
                new ProvisioningOrigin("test:activation", "managed");
        grant(40, origin, false, 2, 2);
        seedCommittedDomainSource(origin);
        ProvisioningActivationRequest request = activation(
                origin, alias(40), null
        );
        AtomicInteger admissionCalls = new AtomicInteger();
        AtomicInteger liveCalls = new AtomicInteger();
        lifecycleAdmission.set(new PersistenceLifecycleAdmissionGateway() {
            @Override
            public java.util.concurrent.CompletionStage<
                    LifecycleAdmissionEvidence> authorize(
                    LifecycleAdmissionRequest admission
            ) {
                admissionCalls.incrementAndGet();
                return java.util.concurrent.CompletableFuture.completedFuture(
                        LifecycleAdmissionEvidence.managed(
                                managedPayload(admission), null
                        )
                );
            }
        });

        OperationWorkflowResult first = adapter
                .provisioningActivationOperations()
                .submit(
                        operationId(41),
                        request,
                        (payload, operation) -> {
                            liveCalls.incrementAndGet();
                            return LiveOperationResult.confirmed(
                                    "managed-spawn"
                            ).completed();
                        }
                ).completion().toCompletableFuture().get(
                        10, TimeUnit.SECONDS
                );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                first.status(),
                () -> String.valueOf(first.failure())
        );
        assertEquals(1, admissionCalls.get());
        assertEquals(1, liveCalls.get());
        assertEquals(2, domainRows(origin));

        OperationWorkflowResult replay = adapter
                .provisioningActivationOperations()
                .submit(
                        operationId(41),
                        request,
                        (payload, operation) -> {
                            liveCalls.incrementAndGet();
                            return LiveOperationResult.confirmed(
                                    "must-not-respawn"
                            ).completed();
                        }
                ).completion().toCompletableFuture().get(
                        10, TimeUnit.SECONDS
                );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, admissionCalls.get());
        assertEquals(1, liveCalls.get());
        assertEquals(LifecycleState.ACTIVE, lifecycle(origin).state());
    }

    private void grant(
            int number,
            ProvisioningOrigin origin,
            boolean command,
            int ownedLimit,
            int activeLimit
    ) throws Exception {
        OperationWorkflowResult result =
                adapter.provisioningOperations().submit(
                        operationId(number),
                        grantRequest(
                                origin, command,
                                ownedLimit, activeLimit
                        )
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status()
        );
    }

    private OperationWorkflowResult activate(
            int number,
            ProvisioningActivationRequest request,
            LiveOperationResult liveResult
    ) throws Exception {
        return adapter.provisioningActivationOperations().submit(
                operationId(number),
                request,
                (payload, operation) -> liveResult.completed()
        ).completion().toCompletableFuture().get(
                10, TimeUnit.SECONDS
        );
    }

    private PopulationDomainAdmissionOperation.Payload managedPayload(
            LifecycleAdmissionRequest request
    ) {
        var source = request.source();
        var managed = request.managedRequest().request();
        OwnerId targetOwner = OwnerId.parse(
                managed.newOwnerUuid().toString()
        );
        return new PopulationDomainAdmissionOperation.Payload(
                UUID.nameUUIDFromBytes((request.operationId().value()
                        + ":lifecycle-admission").getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                )),
                source.profileId(),
                targetOwner,
                source.revision(),
                request.managedRequest().ownershipWorldName(),
                source.ownerId(),
                source.ownerWorldKey(),
                source.state(),
                LifecycleState.ACTIVE,
                "managed-test-group",
                "managed-test-provider",
                1,
                "generation",
                1,
                1,
                Long.MAX_VALUE,
                1,
                List.of(new PopulationDomainAdmissionOperation.DomainInput(
                        "managed-test-domain",
                        PopulationDomainScope.PER_WORLD,
                        request.managedRequest().ownershipWorldName(),
                        0,
                        1,
                        1,
                        100,
                        100,
                        1
                )),
                List.of(),
                request.source().stateChangedAtMs()
        );
    }

    private void seedCommittedDomainSource(ProvisioningOrigin origin)
            throws Exception {
        OperationId sourceOperation = operationId(49);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO operation_envelope(
                        operation_id, idempotency_key, operation_kind,
                        payload_version, payload_json, phase, feature_scope,
                        expected_lifecycle_revision, lease_owner, lease_until_ms,
                        attempt_count, failure_kind, failure_code, created_at_ms,
                        updated_at_ms, durable_at_ms, published_at_ms, terminal_at_ms
                    ) VALUES (?, ?, 'seed_domain', 1, '{}', 'PUBLISHED',
                              'seed', 0, NULL, 0, 0, NULL, NULL,
                              -500, -500, -500, -500, NULL)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, "seed-activation-domain-49");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO operation_participant(
                        operation_id, scope_type, scope_key
                    ) VALUES (?, 'PROFILE', ?), (?, 'OWNER', ?)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, origin.profileId().toString());
                statement.setString(3, sourceOperation.toString());
                statement.setString(4, OWNER.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO population_domain_reservation(
                        operation_id, profile_id, expected_lifecycle_revision,
                        owner_uuid, domain_id, scope_kind, owner_world_key,
                        owned_delta, deployable_delta, weight,
                        snapshotted_max_owned, snapshotted_max_deployable,
                        policy_revision, created_at_ms
                    ) VALUES (?, ?, 0, ?, 'managed-test-domain', 'PER_WORLD',
                              'world-a', 1, 0, 1, 100, 100, 1, -500)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, origin.profileId().toString());
                statement.setString(3, OWNER.toString());
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private CompanionProvisioningRequest grantRequest(
            ProvisioningOrigin origin,
            boolean command,
            int ownedLimit,
            int activeLimit
    ) {
        CompanionLifecycle lifecycle = dormant(origin);
        return new CompanionProvisioningRequest(
                origin,
                new UUID(0, Math.abs(origin.stableKey().hashCode())),
                new CompanionIdentity(
                        origin.profileId(),
                        "Provisioned",
                        "Mini",
                        null,
                        null,
                        "world-a",
                        GRANTED_AT,
                        GRANTED_AT,
                        GRANTED_AT,
                        0
                ),
                lifecycle,
                assignment(origin),
                List.of(policy(ownedLimit, activeLimit)),
                ownedLimit,
                ownedLimit,
                command ? new CommandRosterMembershipDraft(
                        origin.commandSlotId(),
                        new CommandFamilyKey(OWNER, "summon"),
                        origin.profileId(),
                        "companions",
                        true,
                        null,
                        GRANTED_AT
                ) : null,
                command ? 0L : null,
                GRANTED_AT
        );
    }

    private ProvisioningActivationRequest activation(
            ProvisioningOrigin origin,
            NpcAlias alias,
            TimedSummonActivation timed
    ) {
        CompanionLifecycle before = dormant(origin);
        CompanionLifecycle after = new CompanionLifecycle(
                origin.profileId(),
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        alias.toString(), "world-a"
                ),
                new LifecycleRevision(1),
                null,
                ACTIVATED_AT,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
        return new ProvisioningActivationRequest(
                origin,
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        7,
                        List.of(policy(2, 2)),
                        ACTIVATED_AT
                ),
                alias,
                "Mini",
                fullState(alias),
                new CompanionSpawnPlacement(
                        "world-a", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "spawn:" + origin.callerKey(),
                timed,
                ACTIVATED_AT
        );
    }

    private SnapshotCodecRegistry.EncodedSnapshot fullState(
            NpcAlias alias
    ) {
        String payload = "{\"npcUuid\":\"" + alias + "\"}";
        return new SnapshotCodecRegistry.EncodedSnapshot(
                CompanionFullStateProjection.KIND,
                CompanionFullStateProjection.VERSION,
                payload,
                Sha256Hash.ofUtf8(payload)
        );
    }

    private CompanionLifecycle dormant(ProvisioningOrigin origin) {
        return new CompanionLifecycle(
                origin.profileId(),
                OWNER,
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        origin.stableKey()
                ),
                LifecycleRevision.INITIAL,
                null,
                GRANTED_AT,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private PopulationGroupAssignment assignment(
            ProvisioningOrigin origin
    ) {
        return new PopulationGroupAssignment(
                origin.profileId(),
                "Mini",
                List.of(new PopulationGroupMembership(
                        "mod:mini",
                        PopulationGroupScope.GLOBAL
                )),
                7,
                0,
                LifecycleRevision.INITIAL,
                1,
                GRANTED_AT
        );
    }

    private PopulationGroupPolicy policy(
            int ownedLimit,
            int activeLimit
    ) {
        return new PopulationGroupPolicy(
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                ownedLimit,
                activeLimit,
                7
        );
    }

    private TimedSummonLease lease(ProvisioningOrigin origin) {
        return new TimedSummonLease(
                origin.profileId(),
                1,
                new TimedSummonSessionId(
                        new UUID(0, Math.abs(
                                origin.stableKey().hashCode()
                        ))
                ),
                10_000L,
                null,
                new TimedSummonPolicy(
                        "role:timed",
                        7L,
                        10_000,
                        2_000,
                        true,
                        List.of(5_000L)
                ),
                Set.of(),
                ACTIVATED_AT,
                ACTIVATED_AT,
                ACTIVATED_AT
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        LiveOperationResult.confirmed("capture")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("capture-release")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("restore")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop-in")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop-out")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("timed")
                                .completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed(
                                request.spawnReceiptKey()
                        ).completed()
        );
    }

    private ProvisioningRecord provenance(ProvisioningOrigin origin)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteProvisioningStore(connection)
                    .findByProfile(origin.profileId()).orElseThrow();
        }
    }

    private CompanionLifecycle lifecycle(ProvisioningOrigin origin)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(origin.profileId()).orElseThrow();
        }
    }

    private CompanionAlias resolvedAlias(NpcAlias alias)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(alias).orElseThrow();
        }
    }

    private TimedSummonLease timedLease(ProvisioningOrigin origin)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteTimedSummonLeaseStore(connection)
                    .find(origin.profileId()).orElseThrow();
        }
    }

    private long reservationCount(OperationId operationId)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqlitePopulationGroupAdmissionStore(connection)
                    .findByOperation(operationId).size();
        }
    }

    private int domainRows(ProvisioningOrigin origin) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqlitePopulationDomainStore(connection)
                    .profileEvidence(origin.profileId(), null)
                    .committed().size();
        }
    }

    private PopulationGroupBucket bucket() {
        return new PopulationGroupBucket(
                OWNER,
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                null
        );
    }

    private NpcAlias alias(int number) {
        return NpcAlias.parse(String.format(
                "10000000-0000-0000-0000-%012d", number
        ));
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "40000000-0000-0000-0000-%012d", number
        ));
    }
}
