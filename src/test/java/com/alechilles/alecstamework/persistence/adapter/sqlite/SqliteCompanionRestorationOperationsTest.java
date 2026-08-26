package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionLifecycleEventCodec;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionLifecyclePublishedEventMapper;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationEventCodec;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationOutcome;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end alias lease, live receipt, durable restoration, and replay tests. */
class SqliteCompanionRestorationOperationsTest {
    private static final ProvisioningOrigin PROVISIONING =
            new ProvisioningOrigin("hydragon", "soul-bond:owner");
    private static final ProfileId PROFILE =
            PROVISIONING.profileId();
    private static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final SnapshotId SNAPSHOT_ID =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    private static final String SNAPSHOT_JSON = "{\"health\":100}";

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionRestorationOperations restorations;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV2Manager(connections, () -> -10_000).initialize();
        seedDormantProfile(LifecycleState.DEAD_REVIVABLE);
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionRestorationDefinition.INSTANCE
                )),
                units
        );
        restorations = new SqliteCompanionRestorationOperations(
                engine,
                new SqliteOperationPublisher(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -400
                        ),
                        () -> -400
                ),
                () -> -400,
                List.of()
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
    void aliasLeaseAndLifecycleFenceAreDurableBeforeEntityInsertion()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                1,
                (restoration, operation) -> {
                    liveCalls.incrementAndGet();
                    assertEquals(
                            OperationPhase.LIVE_APPLYING,
                            operation.phase()
                    );
                    CompanionAlias lease = alias(TARGET_ALIAS);
                    assertEquals(CompanionAlias.State.LEASED, lease.state());
                    assertEquals(
                            operation.operationId(),
                            lease.leaseOperationId()
                    );
                    CompanionLifecycle fenced = lifecycle();
                    assertEquals(new LifecycleRevision(2), fenced.revision());
                    assertEquals(
                            operation.operationId(),
                            fenced.activeOperationId()
                    );
                    assertEquals(
                            LifecycleState.DEAD_REVIVABLE,
                            fenced.state()
                    );
                    assertTrue(snapshot().current());
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(1, liveCalls.get());
        CompanionRestorationOutcome outcome =
                CompanionRestorationEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );
        assertEquals(TARGET_ALIAS, outcome.targetAlias());
        CompanionLifecycle active = lifecycle();
        assertEquals(LifecycleState.ACTIVE, active.state());
        assertEquals(new LifecycleRevision(3), active.revision());
        assertEquals(
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(), "world-two"
                ),
                active.location()
        );
        assertEquals(OWNER, active.ownerId());
        assertNull(active.activeOperationId());
        assertEquals(
                CompanionAlias.State.CURRENT,
                alias(TARGET_ALIAS).state()
        );
        assertEquals(
                CompanionAlias.State.RETIRED,
                alias(SOURCE_ALIAS).state()
        );
        assertTrue(!snapshot().current());
    }

    @Test
    void retryAndPublishedReplayNeverInsertASecondEntity()
            throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger insertions = new AtomicInteger();
        CompanionRestorationLiveBoundary boundary =
                (restoration, operation) -> {
                    if (resolutions.incrementAndGet() == 1) {
                        return LiveOperationResult.retryable(
                                "target_world_temporarily_unavailable",
                                null
                        ).completed();
                    }
                    insertions.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                };

        OperationWorkflowResult first = submit(2, boundary);
        OperationWorkflowResult second = submit(2, boundary);
        OperationWorkflowResult replay = submit(2, boundary);

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                first.status()
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, second.status());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(2, resolutions.get());
        assertEquals(1, insertions.get());
    }

    @Test
    void entityAbsenceIsRetryableAndCannotFinalizeRestoration()
            throws Exception {
        OperationWorkflowResult result = submit(
                3,
                (restoration, operation) -> LiveOperationResult.retryable(
                        "spawn_receipt_not_found",
                        null
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                result.status()
        );
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                lifecycle().state()
        );
        assertNotNull(lifecycle().activeOperationId());
        assertEquals(
                CompanionAlias.State.LEASED,
                alias(TARGET_ALIAS).state()
        );
        assertTrue(snapshot().current());
    }

    @Test
    void ambiguousInsertionQuarantinesOnlyOperationAndProfile()
            throws Exception {
        OperationWorkflowResult result = submit(
                4,
                (restoration, operation) -> LiveOperationResult.unknown(
                        "spawn_receipt_read_failed",
                        null
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                result.status()
        );
        assertEquals(OperationPhase.UNKNOWN, result.operation().phase());
        try (Connection connection = connections.openReadConnection()) {
            SqliteIncidentStore incidents = new SqliteIncidentStore(connection);
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.operation(operationId(4))
                    ).orElseThrow().state()
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.profile(PROFILE)
                    ).orElseThrow().state()
            );
            assertTrue(
                    incidents.findQuarantine(OperationScope.owner(OWNER))
                            .isEmpty()
            );
            assertTrue(
                    incidents.findQuarantine(OperationScope.global())
                            .isEmpty()
            );
        }
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                lifecycle().state()
        );
        assertEquals(
                CompanionAlias.State.LEASED,
                alias(TARGET_ALIAS).state()
        );
        assertTrue(snapshot().current());
    }

    @Test
    void durableCommitWaitsForAsynchronousWorldThreadEvidence()
            throws Exception {
        CompletableFuture<LiveOperationResult> live =
                new CompletableFuture<>();
        CountDownLatch invoked = new CountDownLatch(1);

        SqliteCompanionRestorationOperations.Submission submission =
                restorations.submit(
                        operationId(5),
                        new IdempotencyKey("restoration-5"),
                        restorationRequest(),
                        (restoration, operation) -> {
                            invoked.countDown();
                            return live;
                        }
                );

        assertTrue(invoked.await(10, TimeUnit.SECONDS));
        assertFalse(submission.completion().toCompletableFuture().isDone());
        assertEquals(
                CompanionAlias.State.LEASED,
                alias(TARGET_ALIAS).state()
        );
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                lifecycle().state()
        );

        live.complete(LiveOperationResult.confirmed(
                "spawn_receipt_confirmed"
        ));
        OperationWorkflowResult result = submission.completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
    }

    @Test
    void managedFreeRestorationRepairsMissingGroupAssignment()
            throws Exception {
        seedCommittedDomainSource();
        AtomicInteger admissionCalls = new AtomicInteger();
        AtomicInteger liveCalls = new AtomicInteger();
        SqliteLifecycleAdmissionBinding binding =
                new SqliteLifecycleAdmissionBinding();
        binding.bind(request -> {
            admissionCalls.incrementAndGet();
            assertEquals(
                    "world-two",
                    request.managedRequest().request().destination().worldName()
            );
            assertEquals(
                    -1,
                    request.managedRequest().request().destination().chunkX()
            );
            assertEquals(
                    -1,
                    request.managedRequest().request().destination().chunkZ()
            );
            return CompletableFuture.completedFuture(
                    managedEvidence(request.operationId())
            );
        });
        SqliteCompanionRestorationOperations managed =
                newManagedRestorations(binding);
        CompanionRestorationLiveBoundary boundary = (request, operation) -> {
            liveCalls.incrementAndGet();
            return LiveOperationResult.confirmed(
                    "spawn_receipt_confirmed"
            ).completed();
        };

        OperationWorkflowResult first = managed.submit(
                operationId(30),
                new IdempotencyKey("restoration-30"),
                restorationRequest(),
                boundary
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        OperationWorkflowResult replay = managed.submit(
                operationId(30),
                new IdempotencyKey("restoration-30"),
                restorationRequest(),
                boundary
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                first.status(), String.valueOf(first.failure())
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, admissionCalls.get());
        assertEquals(1, liveCalls.get());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        try (Connection connection = connections.openReadConnection()) {
            SqlitePopulationDomainStore domains =
                    new SqlitePopulationDomainStore(connection);
            assertEquals(
                    0,
                    domains.counts(new PopulationDomainBucket(
                            OWNER,
                            "managed-test-domain",
                            PopulationDomainScope.PER_WORLD,
                            "world"
                    )).committedOwned()
            );
            assertEquals(
                    1,
                    domains.counts(new PopulationDomainBucket(
                            OWNER,
                            "managed-test-domain",
                            PopulationDomainScope.PER_WORLD,
                            "world-two"
                    )).committedDeployable()
            );
            PopulationGroupAssignment assignment =
                    new SqlitePopulationGroupAssignmentStore(connection)
                            .find(PROFILE).orElseThrow();
            assertEquals("role", assignment.roleId());
            assertEquals(
                    List.of(new PopulationGroupMembership(
                            "managed-test-group", PopulationGroupScope.GLOBAL
                    )),
                    assignment.memberships()
            );
            assertEquals(new LifecycleRevision(3),
                    assignment.sourceLifecycleRevision());
        }
    }

    /** Protects revival of companions made dormant before domain convergence. */
    @Test
    void managedFreeRestorationRepairsLegacyDeployedClaim()
            throws Exception {
        seedLegacyDeployedDomainSource();
        SqliteLifecycleAdmissionBinding binding =
                new SqliteLifecycleAdmissionBinding();
        binding.bind(request -> CompletableFuture.completedFuture(
                legacyManagedEvidence(request.operationId())
        ));
        SqliteCompanionRestorationOperations managed =
                newManagedRestorations(binding);

        OperationWorkflowResult result = managed.submit(
                operationId(31),
                new IdempotencyKey("restoration-31"),
                restorationRequest(),
                (request, operation) -> LiveOperationResult.confirmed(
                        "spawn_receipt_confirmed"
                ).completed()
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(), String.valueOf(result.failure())
        );
        try (Connection connection = connections.openReadConnection()) {
            assertEquals(
                    1,
                    new SqlitePopulationDomainStore(connection).counts(
                            new PopulationDomainBucket(
                                    OWNER,
                                    "managed-legacy-deployed",
                                    PopulationDomainScope.GLOBAL,
                                    null
                            )
                    ).committedDeployable()
            );
        }
    }

    @Test
    void managedEvidenceCannotBeInjectedIntoNeutralRestoration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRestorationRequest(
                        PROFILE,
                        new LifecycleRevision(1),
                        LifecycleState.DEAD_REVIVABLE,
                        sourceSnapshot(true),
                        LifecycleState.PROVISIONED_DORMANT,
                        null,
                        null,
                        null,
                        null,
                        -600,
                        managedEvidence(operationId(31))
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRestorationRequest(
                        PROFILE,
                        new LifecycleRevision(1),
                        LifecycleState.LOST,
                        lostSnapshot(),
                        LifecycleState.ACTIVE,
                        restorationProjection(),
                        TARGET_ALIAS,
                        new CompanionSpawnPlacement(
                                "world-two", -12.5, -63.05, -4.5,
                                -0.25f, -1.5f, -0.5f
                        ),
                        "spawn-receipt",
                        -600,
                        managedEvidence(operationId(32))
                )
        );
    }

    @Test
    void provisionedRevivalPublishesSelfContainedSemanticEvent()
            throws Exception {
        seedProvisioning();

        OperationWorkflowResult result = submit(
                6,
                (restoration, operation) -> LiveOperationResult.confirmed(
                        "spawn_receipt_confirmed"
                ).completed()
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(4, result.events().size());
        var semantic = result.events().stream()
                .filter(event -> ProvisionedCompanionLifecycleEventCodec
                        .REVIVED_EVENT_TYPE.equals(event.eventType()))
                .findFirst()
                .orElseThrow();
        var mapped =
                ProvisionedCompanionLifecyclePublishedEventMapper.mapRevival(
                        semantic, true, -390
                );
        assertEquals(PROVISIONING.callerNamespace(), mapped.callerNamespace());
        assertEquals(PROVISIONING.callerKey(), mapped.provisioningKey());
        assertEquals(PROFILE.toString(), mapped.profileId());
        assertEquals(OWNER.value(), mapped.ownerUuid());
        assertEquals(TARGET_ALIAS.value(), mapped.newNpcUuid());
        assertEquals(1, mapped.oldProfileRevision());
        assertEquals(3, mapped.newProfileRevision());
        assertTrue(mapped.recovered());
        assertEquals(-400, mapped.revivedAtMs());
    }

    @Test
    void provisionedDormantRevivalIsDatabaseOnlyAndPreservesAuthorities()
            throws Exception {
        seedProvisioning();
        AuthorityState authorities = seedFeatureAuthorities();
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult first = restorations.submit(
                operationId(7),
                new IdempotencyKey("restoration-7"),
                dormantRestorationRequest(),
                (restoration, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "must_not_run"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        OperationWorkflowResult replay = restorations.submit(
                operationId(7),
                new IdempotencyKey("restoration-7"),
                dormantRestorationRequest(),
                (restoration, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "must_not_run"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                first.status(),
                () -> String.valueOf(first.failure())
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(0, liveCalls.get());
        CompanionLifecycle dormant = lifecycle();
        assertEquals(LifecycleState.PROVISIONED_DORMANT, dormant.state());
        assertEquals(new LifecycleRevision(2), dormant.revision());
        assertEquals(
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        PROVISIONING.stableKey()
                ),
                dormant.location()
        );
        assertEquals(OWNER, dormant.ownerId());
        assertNull(dormant.activeOperationId());
        assertTrue(snapshot().current());
        assertEquals(
                CompanionAlias.State.RETIRED,
                alias(SOURCE_ALIAS).state()
        );
        assertEquals(authorities, authorityState());

        CompanionRestorationOutcome outcome =
                CompanionRestorationEventCodec.decode(
                        first.events().getFirst().payloadVersion(),
                        first.events().getFirst().payloadJson()
                );
        assertEquals(
                LifecycleState.PROVISIONED_DORMANT,
                outcome.targetState()
        );
        assertNull(outcome.targetAlias());
        var semantic = first.events().stream()
                .filter(event -> ProvisionedCompanionLifecycleEventCodec
                        .REVIVED_EVENT_TYPE.equals(event.eventType()))
                .findFirst()
                .orElseThrow();
        var mapped =
                ProvisionedCompanionLifecyclePublishedEventMapper.mapRevival(
                        semantic, false, -390
                );
        assertNull(mapped.newNpcUuid());
        assertEquals(
                com.alechilles.alecstamework.api
                        .PopulationCompanionLifecycle.PROVISIONED_DORMANT,
                mapped.lifecycle()
        );
        assertEquals(
                com.alechilles.alecstamework.api
                        .CompanionProvisioningProjectionStatus.NOT_REQUESTED,
                mapped.projectionStatus()
        );
        assertEquals(1, mapped.oldProfileRevision());
        assertEquals(2, mapped.newProfileRevision());
    }

    @Test
    void provisionedDormantRevivalRequiresExistingEntitlement()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = restorations.submit(
                operationId(8),
                new IdempotencyKey("restoration-8"),
                dormantRestorationRequest(),
                (restoration, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "must_not_run"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(0, liveCalls.get());
        assertEquals(LifecycleState.DEAD_REVIVABLE, lifecycle().state());
        assertEquals(new LifecycleRevision(1), lifecycle().revision());
        assertTrue(snapshot().current());
    }

    private OperationWorkflowResult submit(
            int number,
            CompanionRestorationLiveBoundary boundary
    ) throws Exception {
        SqliteCompanionRestorationOperations.Submission submission =
                restorations.submit(
                        operationId(number),
                        new IdempotencyKey("restoration-" + number),
                        restorationRequest(),
                        boundary
                );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private CompanionRestorationRequest restorationRequest() {
        return new CompanionRestorationRequest(
                PROFILE,
                new LifecycleRevision(1),
                LifecycleState.DEAD_REVIVABLE,
                sourceSnapshot(true),
                restorationProjection(),
                TARGET_ALIAS,
                new CompanionSpawnPlacement(
                        "world-two", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "spawn-receipt",
                -600
        );
    }

    private CompanionRestorationRequest dormantRestorationRequest() {
        return CompanionRestorationRequest.reviveProvisionedDormant(
                PROFILE,
                new LifecycleRevision(1),
                sourceSnapshot(true),
                -600
        );
    }

    private RestorationProjection restorationProjection() {
        String payload = "{\"state\":\"frozen\"}";
        return new RestorationProjection(
                SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        payload,
                        Sha256Hash.ofUtf8(payload)
                )
        );
    }

    private SqliteCompanionRestorationOperations newManagedRestorations(
            SqliteLifecycleAdmissionBinding binding
    ) {
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionRestorationDefinition.INSTANCE
                )),
                units
        );
        SqliteOperationPublisher publisher = new SqliteOperationPublisher(
                engine,
                new SqliteOperationEvidenceReader(reads),
                new ProjectionCoordinator(
                        new SqliteProjectionGateway(reads, units),
                        ProjectionRetryPolicy.DEFAULT,
                        () -> -400
                ),
                () -> -400
        );
        return new SqliteCompanionRestorationOperations(
                engine,
                publisher,
                () -> -400,
                new SqliteOperationReader(reads),
                binding,
                new SqliteLifecycleAdmissionSourceReader(reads),
                List.of()
        );
    }

    private LifecycleAdmissionEvidence managedEvidence(OperationId operationId) {
        PopulationDomainAdmissionOperation.Payload payload =
                new PopulationDomainAdmissionOperation.Payload(
                        UUID.nameUUIDFromBytes((operationId.value()
                                + ":lifecycle-admission").getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )),
                        PROFILE,
                        OWNER,
                        new LifecycleRevision(1),
                        "world-two",
                        OWNER,
                        "world",
                        LifecycleState.DEAD_REVIVABLE,
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
                                "world-two",
                                1,
                                1,
                                1,
                                100,
                                100,
                                1
                        )),
                        List.of(),
                        -400
                );
        CompanionLifecycle before = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.DEAD_REVIVABLE,
                LifecycleLocation.none(),
                new LifecycleRevision(1),
                null,
                -10_000,
                new ReconciliationGeneration(4),
                null,
                "world"
        );
        CompanionLifecycle after = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(), "world-two"
                ),
                new LifecycleRevision(2),
                operationId,
                -400,
                new ReconciliationGeneration(4),
                null,
                "world-two"
        );
        PopulationGroupPolicy policy = new PopulationGroupPolicy(
                "managed-test-group",
                PopulationGroupScope.GLOBAL,
                100,
                100,
                1
        );
        PopulationAdmissionComposition composition =
                new PopulationAdmissionComposition(
                        null,
                        new PopulationGroupTransitionAdmissionRequest(
                                before,
                                after,
                                0,
                                1,
                                List.of(policy),
                                -400
                        )
                );
        return LifecycleAdmissionEvidence.managed(payload, composition);
    }

    private LifecycleAdmissionEvidence legacyManagedEvidence(
            OperationId operationId
    ) {
        PopulationDomainAdmissionOperation.Payload payload =
                new PopulationDomainAdmissionOperation.Payload(
                        UUID.nameUUIDFromBytes((operationId.value()
                                + ":lifecycle-admission").getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )),
                        PROFILE,
                        OWNER,
                        new LifecycleRevision(1),
                        "world-two",
                        OWNER,
                        "world",
                        LifecycleState.DEAD_REVIVABLE,
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
                                "managed-legacy-deployed",
                                PopulationDomainScope.GLOBAL,
                                null,
                                0,
                                1,
                                1,
                                0,
                                6,
                                1
                        )),
                        List.of(),
                        -400
                );
        return LifecycleAdmissionEvidence.managed(payload, null);
    }

    private void seedCommittedDomainSource() throws Exception {
        OperationId sourceOperation = operationId(190);
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
                statement.setString(2, "seed-domain-190");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO operation_participant(operation_id, scope_type, scope_key)
                    VALUES (?, 'PROFILE', ?), (?, 'OWNER', ?)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, PROFILE.toString());
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
                              'world', 1, 0, 1, 100, 100, 1, -500)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, PROFILE.toString());
                statement.setString(3, OWNER.toString());
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private void seedLegacyDeployedDomainSource() throws Exception {
        OperationId sourceOperation = operationId(191);
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
                statement.setString(2, "seed-domain-191");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO population_domain_reservation(
                        operation_id, profile_id, expected_lifecycle_revision,
                        owner_uuid, domain_id, scope_kind, owner_world_key,
                        owned_delta, deployable_delta, weight,
                        snapshotted_max_owned, snapshotted_max_deployable,
                        policy_revision, created_at_ms
                    ) VALUES (?, ?, 0, ?, 'managed-legacy-deployed',
                              'GLOBAL', '', 0, 1, 1, 0, 6, 1, -500)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, PROFILE.toString());
                statement.setString(3, OWNER.toString());
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private void seedDormantProfile(LifecycleState state) throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
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
            CompanionLifecycle active = new CompanionLifecycle(
                    PROFILE,
                    OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(
                            SOURCE_ALIAS.toString(), "world"
                    ),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    new ReconciliationGeneration(4),
                    null
            );
            transaction.lifecycles().create(active);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO companion_alias(
                        npc_uuid, profile_id, alias_generation, alias_state,
                        lease_operation_id, mapped_at_ms, retired_at_ms
                    ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                    """)) {
                statement.setString(1, SOURCE_ALIAS.toString());
                statement.setString(2, PROFILE.toString());
                statement.setLong(3, -11_000);
                statement.executeUpdate();
            }
            transaction.snapshots().replaceCurrent(sourceSnapshot(true));
            transaction.identities().retireAlias(SOURCE_ALIAS, -10_000);
            transaction.lifecycles().transition(new LifecycleTransition(
                    LifecycleRevision.INITIAL,
                    null,
                    new CompanionLifecycle(
                            PROFILE,
                            OWNER,
                            state,
                            LifecycleLocation.none(),
                            new LifecycleRevision(1),
                            null,
                            -10_000,
                            new ReconciliationGeneration(4),
                            null,
                            "world"
                    )
            ));
            connection.commit();
        }
    }

    private void seedProvisioning() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationId creation = operationId(90);
            transaction.operations().prepare(new PreparedOperation(
                    creation,
                    PROVISIONING.operationKey(),
                    CompanionProvisioningDefinition.KIND,
                    1,
                    "{}",
                    SqliteCompanionProvisioningOperations.FEATURE_SCOPE,
                    null,
                    List.of(OperationScope.profile(PROFILE)),
                    -9_000
            ));
            transaction.provisioning().create(new ProvisioningRecord(
                    PROFILE,
                    PROVISIONING,
                    null,
                    1,
                    creation,
                    -9_000
            ));
            connection.commit();
        }
    }

    private AuthorityState seedFeatureAuthorities() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            CommandFamilyKey family =
                    new CommandFamilyKey(OWNER, "hydragon");
            new SqliteCommandRosterStore(connection).upsert(
                    0,
                    null,
                    new CommandRosterMembershipDraft(
                            CommandRosterSlotId.parse(
                                    "70000000-0000-0000-0000-000000000001"
                            ),
                            family,
                            PROFILE,
                            "bonded",
                            true,
                            null,
                            -8_000
                    )
            );
            new SqlitePopulationGroupAssignmentStore(connection).replace(
                    null,
                    new PopulationGroupAssignment(
                            PROFILE,
                            "role",
                            List.of(new PopulationGroupMembership(
                                    "bonded",
                                    PopulationGroupScope.GLOBAL
                            )),
                            1,
                            0,
                            new LifecycleRevision(1),
                            1,
                            -8_000
                    )
            );
            new SqliteTimedSummonLeaseStore(connection).replace(
                    null,
                    new TimedSummonLease(
                            PROFILE,
                            1,
                            null,
                            null,
                            -7_000L,
                            new TimedSummonPolicy(
                                    "hydragon",
                                    1L,
                                    60_000,
                                    10_000,
                                    true,
                                    List.of(30_000L)
                            ),
                            Set.of(),
                            null,
                            -8_000,
                            -8_000
                    )
            );
            connection.commit();
        }
        return authorityState();
    }

    private AuthorityState authorityState() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            CommandRoster roster =
                    new SqliteCommandRosterStore(connection)
                            .findRoster(
                                    new CommandFamilyKey(OWNER, "hydragon")
                            )
                            .orElseThrow();
            PopulationGroupAssignment groups =
                    transaction.populationGroups()
                            .findAssignment(PROFILE)
                            .orElseThrow();
            TimedSummonLease timed =
                    transaction.timedSummons()
                            .find(PROFILE)
                            .orElseThrow();
            ProvisioningRecord provisioning =
                    transaction.provisioning()
                            .findByProfile(PROFILE)
                            .orElseThrow();
            return new AuthorityState(
                    roster, groups, timed, provisioning
            );
        }
    }

    private CompanionSnapshot sourceSnapshot(boolean current) {
        return new CompanionSnapshot(
                SNAPSHOT_ID,
                PROFILE,
                DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind(),
                1,
                SNAPSHOT_JSON,
                Sha256Hash.ofUtf8(SNAPSHOT_JSON),
                LifecycleRevision.INITIAL,
                current,
                -10_000
        );
    }

    private CompanionSnapshot lostSnapshot() {
        return new CompanionSnapshot(
                SNAPSHOT_ID,
                PROFILE,
                DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL.snapshotKind(),
                1,
                SNAPSHOT_JSON,
                Sha256Hash.ofUtf8(SNAPSHOT_JSON),
                LifecycleRevision.INITIAL,
                true,
                -10_000
        );
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private CompanionAlias alias(NpcAlias alias) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(alias)
                    .orElseThrow();
        }
    }

    private CompanionSnapshot snapshot() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findById(SNAPSHOT_ID)
                    .orElseThrow();
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d",
                number
        ));
    }

    private record AuthorityState(
            CommandRoster roster,
            PopulationGroupAssignment groups,
            TimedSummonLease timed,
            ProvisioningRecord provisioning
    ) {
    }
}
