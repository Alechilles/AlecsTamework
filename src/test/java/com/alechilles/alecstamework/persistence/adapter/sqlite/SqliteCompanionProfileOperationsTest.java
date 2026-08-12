package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationEventCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationOutcome;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.PublicImportRecoveryProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end operation tests for atomic profile identity and tool-link mutations. */
class SqliteCompanionProfileOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final NpcAlias ALIAS_A =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS_B =
            NpcAlias.parse("30000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER_A =
            OwnerId.parse("10000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER_B =
            OwnerId.parse("10000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_A =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_B =
            UUID.fromString("50000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionProfileOperations operations;
    private RevisionConsumer consumer;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        consumer = new RevisionConsumer();
        operations = new SqliteCompanionProfileOperations(
                new SqliteDatabaseOperationCoordinator(
                        new SqliteOperationEngine(
                                new OperationDefinitionRegistry(
                                        List.of(CompanionProfileMutationDefinition.INSTANCE)
                                ),
                                units
                        ),
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -5_000
                        ),
                        () -> -5_000
                ),
                List.of(consumer)
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
    void createsUpdatesReplacesLinksAndPublishesStaleDenial() throws Exception {
        CompanionProfileMutationOutcome created = submit(
                1,
                "profile-create",
                create("Companion", List.of(link(TOOL_B, -9_000), link(TOOL_A, -9_000)))
        );
        assertEquals(CompanionProfileMutationOutcome.Status.CREATED, created.status());
        assertEquals(0, created.metadataRevision());
        assertEquals(List.of(TOOL_A, TOOL_B), storedToolIds());

        CompanionProfileMutation.Update update = new CompanionProfileMutation.Update(
                identity(1, "Updated", -8_000),
                0,
                List.of(link(TOOL_B, -8_000)),
                -8_000
        );
        CompanionProfileMutationOutcome updated =
                submit(2, "profile-update", update);
        assertEquals(CompanionProfileMutationOutcome.Status.UPDATED, updated.status());
        assertEquals(1, updated.metadataRevision());
        assertEquals("Updated", storedIdentity().displayName());
        assertEquals(List.of(TOOL_B), storedToolIds());

        CompanionProfileMutationOutcome stale = submit(
                3,
                "profile-update-stale",
                new CompanionProfileMutation.Update(
                        identity(1, "Stale", -7_000),
                        0,
                        List.of(),
                        -7_000
                )
        );
        assertEquals(
                CompanionProfileMutationOutcome.Status.REVISION_MISMATCH,
                stale.status()
        );
        assertEquals(1, stale.metadataRevision());
        assertEquals("Updated", storedIdentity().displayName());
        assertEquals(List.of(TOOL_B), storedToolIds());

        CompanionProfileMutationOutcome replay =
                submit(2, "profile-update", update);
        assertEquals(updated, replay);
        assertEquals(2, consumer.appliedEvents);
    }

    @Test
    void repeatedLogicalCreateIsUnchangedButDifferentRecordConflicts()
            throws Exception {
        CompanionProfileMutation.Create create =
                create("Companion", List.of(link(TOOL_A, -9_000)));
        submit(1, "profile-create", create);

        CompanionProfileMutationOutcome unchanged =
                submit(2, "profile-create-same", create);
        assertEquals(
                CompanionProfileMutationOutcome.Status.UNCHANGED,
                unchanged.status()
        );

        CompanionProfileMutationOutcome conflict = submit(
                3,
                "profile-create-conflict",
                create("Different", List.of(link(TOOL_A, -9_000)))
        );
        assertEquals(
                CompanionProfileMutationOutcome.Status.CONFLICT,
                conflict.status()
        );
        assertEquals("Companion", storedIdentity().displayName());
    }

    @Test
    void adoptsLiveIdentityAliasOwnerLifecycleAndLinksAtomically()
            throws Exception {
        CompanionProfileMutation.AdoptLive adoption = adoption(
                PROFILE,
                ALIAS_A,
                OWNER_A,
                "Companion",
                List.of(link(PROFILE, TOOL_A, -9_000))
        );

        CompanionProfileMutationOutcome created =
                submit(1, "profile-adopt", adoption);

        assertEquals(CompanionProfileMutationOutcome.Status.CREATED, created.status());
        assertEquals(adoption.identity(), storedIdentity());
        assertEquals(adoption.initialLifecycle(), storedLifecycle(PROFILE));
        assertEquals(ALIAS_A, storedAlias(ALIAS_A).alias());
        assertEquals(PROFILE, storedAlias(ALIAS_A).profileId());
        assertEquals(0, storedAlias(ALIAS_A).generation());
        assertEquals(
                com.alechilles.alecstamework.companion.identity.CompanionAlias.State.CURRENT,
                storedAlias(ALIAS_A).state()
        );
        assertEquals(List.of(TOOL_A), storedToolIds());

        CompanionProfileMutationOutcome retry =
                submit(2, "profile-adopt-retry", adoption);

        assertEquals(
                CompanionProfileMutationOutcome.Status.UNCHANGED,
                retry.status()
        );
        assertEquals(0, storedAlias(ALIAS_A).generation());
    }

    @Test
    void liveAdoptionReturnsTypedConflictForDifferentIdentityOwnerOrAlias()
            throws Exception {
        CompanionProfileMutation.AdoptLive adoption = adoption(
                PROFILE,
                ALIAS_A,
                OWNER_A,
                "Companion",
                List.of(link(PROFILE, TOOL_A, -9_000))
        );
        submit(1, "profile-adopt", adoption);

        assertEquals(
                CompanionProfileMutationOutcome.Status.CONFLICT,
                submit(
                        2,
                        "profile-adopt-identity-conflict",
                        adoption(
                                PROFILE,
                                ALIAS_A,
                                OWNER_A,
                                "Different",
                                adoption.toolLinks()
                        )
                ).status()
        );
        assertEquals(
                CompanionProfileMutationOutcome.Status.CONFLICT,
                submit(
                        3,
                        "profile-adopt-owner-conflict",
                        adoption(
                                PROFILE,
                                ALIAS_A,
                                null,
                                "Companion",
                                adoption.toolLinks()
                        )
                ).status()
        );
        assertEquals(
                CompanionProfileMutationOutcome.Status.CONFLICT,
                submit(
                        4,
                        "profile-adopt-alias-conflict",
                        adoption(
                                PROFILE,
                                ALIAS_B,
                                OWNER_A,
                                "Companion",
                                adoption.toolLinks()
                        )
                ).status()
        );

        assertEquals(adoption.identity(), storedIdentity());
        assertEquals(adoption.initialLifecycle(), storedLifecycle(PROFILE));
        assertEquals(ALIAS_A, storedAlias(ALIAS_A).alias());
        assertFalse(aliasExists(ALIAS_B));
    }

    @Test
    void adoptsUnownedWildLiveProfileWithoutInventingAnOwnerBucket()
            throws Exception {
        CompanionProfileMutation.AdoptLive adoption = adoption(
                PROFILE,
                ALIAS_A,
                null,
                "Wild Companion",
                List.of()
        );

        CompanionProfileMutationOutcome created =
                submit(1, "profile-adopt-wild", adoption);

        assertEquals(CompanionProfileMutationOutcome.Status.CREATED, created.status());
        CompanionLifecycle lifecycle = storedLifecycle(PROFILE);
        assertEquals(adoption.initialLifecycle(), lifecycle);
        assertNull(lifecycle.ownerId());
        assertNull(lifecycle.ownerWorldKey());
        assertEquals(LifecycleState.ACTIVE, lifecycle.state());
        assertEquals(
                LifecycleLocation.liveEntity(ALIAS_A.toString(), "world"),
                lifecycle.location()
        );
        assertEquals(PROFILE, storedAlias(ALIAS_A).profileId());
    }

    @Test
    void reconcilesImportedSealedAbsenceToUnloadedAtomically() throws Exception {
        CompanionProfileMutation.Create imported =
                new CompanionProfileMutation.Create(
                        identity(0, "Imported", -9_000),
                        new CompanionLifecycle(
                                PROFILE, OWNER_A, LifecycleState.UNRESOLVED,
                                LifecycleLocation.unresolved(),
                                LifecycleRevision.INITIAL, null, -9_000,
                                ReconciliationGeneration.INITIAL, null,
                                "owner-world"
                        ),
                        List.of(),
                        -9_000
                );
        submit(1, "profile-imported-unloaded", imported);
        insertCurrentAlias(ALIAS_A);
        CompanionProfileMutation.ReconcileUnloaded reconciliation =
                new CompanionProfileMutation.ReconcileUnloaded(
                        PROFILE, LifecycleRevision.INITIAL,
                        ReconciliationGeneration.INITIAL, ALIAS_A, -8_000
                );

        OperationWorkflowResult result = submitAsync(
                2, "profile-reconcile-unloaded", reconciliation
        ).get(10, TimeUnit.SECONDS);

        assertEquals(3, result.events().size());
        assertEquals(
                CompanionProfileMutationOutcome.Status.UPDATED,
                CompanionProfileMutationEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                ).status()
        );
        CompanionLifecycle lifecycle = storedLifecycle(PROFILE);
        assertEquals(LifecycleState.UNLOADED, lifecycle.state());
        assertEquals(LifecycleLocation.none(), lifecycle.location());
        assertEquals(new LifecycleRevision(1), lifecycle.revision());
        assertEquals(new ReconciliationGeneration(1),
                lifecycle.lastReconciledGeneration());
        assertEquals("owner-world", lifecycle.ownerWorldKey());
        assertEquals(ALIAS_A, storedAlias(ALIAS_A).alias());
    }

    @Test
    void reconcilesExactMissingActiveRecallToUnloadedBeforeLostRecovery()
            throws Exception {
        CompanionProfileMutation.AdoptLive adoption = adoption(
                PROFILE,
                ALIAS_A,
                OWNER_A,
                "Companion",
                List.of(link(PROFILE, TOOL_A, -9_000))
        );
        submit(1, "profile-adopt-active-recall", adoption);
        CompanionProfileMutation.ReconcileMissingActive reconciliation =
                new CompanionProfileMutation.ReconcileMissingActive(
                        PROFILE,
                        LifecycleRevision.INITIAL,
                        ReconciliationGeneration.INITIAL,
                        ALIAS_A,
                        OWNER_A,
                        "world",
                        -8_000,
                        -7_000
                );

        OperationWorkflowResult result = submitAsync(
                2, "profile-reconcile-missing-active", reconciliation
        ).get(10, TimeUnit.SECONDS);
        CompanionProfileMutationOutcome outcome =
                CompanionProfileMutationEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(3, result.events().size());
        assertEquals(CompanionProfileMutationOutcome.Status.UPDATED,
                outcome.status());
        CompanionLifecycle lifecycle = storedLifecycle(PROFILE);
        assertEquals(LifecycleState.UNLOADED, lifecycle.state());
        assertEquals(LifecycleLocation.none(), lifecycle.location());
        assertEquals(new LifecycleRevision(1), lifecycle.revision());
        assertEquals(new ReconciliationGeneration(1),
                lifecycle.lastReconciledGeneration());
        assertEquals(CompanionAlias.State.CURRENT,
                storedAlias(ALIAS_A).state());
    }

    @Test
    void missingActiveRecallDoesNotTrustAWorldThatWasNotProbed()
            throws Exception {
        CompanionProfileMutation.AdoptLive adoption = adoption(
                PROFILE, ALIAS_A, OWNER_A, "Companion", List.of()
        );
        submit(1, "profile-adopt-active-world-fence", adoption);
        CompanionProfileMutation.ReconcileMissingActive reconciliation =
                new CompanionProfileMutation.ReconcileMissingActive(
                        PROFILE,
                        LifecycleRevision.INITIAL,
                        ReconciliationGeneration.INITIAL,
                        ALIAS_A,
                        OWNER_A,
                        "different-world",
                        -8_000,
                        -7_000
                );

        CompanionProfileMutationOutcome outcome = submit(
                2, "profile-reconcile-missing-active-world-fence",
                reconciliation
        );

        assertEquals(CompanionProfileMutationOutcome.Status.UNCHANGED,
                outcome.status());
        assertEquals(LifecycleState.ACTIVE,
                storedLifecycle(PROFILE).state());
        assertEquals(CompanionAlias.State.CURRENT,
                storedAlias(ALIAS_A).state());
    }

    @Test
    void explicitRecallConvertsExactImportRecoveryToLostOnce()
            throws Exception {
        CompanionProfileMutation.Create imported =
                new CompanionProfileMutation.Create(
                        identity(0, "Imported", -9_000),
                        new CompanionLifecycle(
                                PROFILE, OWNER_A, LifecycleState.UNRESOLVED,
                                LifecycleLocation.unresolved(),
                                LifecycleRevision.INITIAL, null, -9_000,
                                ReconciliationGeneration.INITIAL, null,
                                "owner-world"
                        ),
                        List.of(),
                        -9_000
                );
        submit(1, "profile-import-recovery-create", imported);
        insertCurrentAlias(ALIAS_A);
        String payload = """
                {"version":"1","npcUuid":"%s","roleId":"role","commandLinks":{"ownerId":"%s"},"owner":{"ownerId":"%s"}}
                """.formatted(ALIAS_A, OWNER_A, OWNER_A).trim();
        CompanionSnapshot source = new CompanionSnapshot(
                SnapshotId.parse(
                        "60000000-0000-0000-0000-000000000001"
                ),
                PROFILE,
                PublicImportRecoveryProjection.KIND,
                PublicImportRecoveryProjection.VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                true,
                -8_500
        );
        insertSnapshot(source);
        OperationWorkflowResult unloaded = submitAsync(
                2,
                "profile-import-recovery-unloaded",
                new CompanionProfileMutation.ReconcileUnloaded(
                        PROFILE,
                        LifecycleRevision.INITIAL,
                        ReconciliationGeneration.INITIAL,
                        ALIAS_A,
                        -8_000
                )
        ).get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED,
                unloaded.status());
        CompanionProfileMutation.RecoverImportedMissing recovery =
                new CompanionProfileMutation.RecoverImportedMissing(
                        PROFILE,
                        new LifecycleRevision(1),
                        0,
                        ALIAS_A,
                        OWNER_A,
                        source.snapshotId(),
                        source.payloadHash(),
                        -7_500,
                        -7_000
                );
        CompanionProfileMutation.RecoverImportedMissing staleMetadata =
                new CompanionProfileMutation.RecoverImportedMissing(
                        PROFILE,
                        new LifecycleRevision(1),
                        1,
                        ALIAS_A,
                        OWNER_A,
                        source.snapshotId(),
                        source.payloadHash(),
                        -7_500,
                        -7_000
                );

        OperationWorkflowResult rejected = submitAsync(
                3,
                "profile-import-recovery-stale-metadata",
                staleMetadata
        ).get(10, TimeUnit.SECONDS);
        assertEquals(
                OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                rejected.status()
        );
        assertEquals(LifecycleState.UNLOADED,
                storedLifecycle(PROFILE).state());
        assertEquals(ALIAS_A, storedCurrentAlias().alias());
        assertTrue(storedSnapshot(source.snapshotId()).current());
        assertNull(storedCurrentSnapshotOrNull(new SnapshotKind("lost")));

        OperationWorkflowResult result = submitAsync(
                4,
                "profile-import-recovery",
                recovery
        ).get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(3, result.events().size());
        assertEquals(
                CompanionProfileMutationOutcome.Status.UPDATED,
                CompanionProfileMutationEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                ).status()
        );
        CompanionLifecycle lifecycle = storedLifecycle(PROFILE);
        assertEquals(LifecycleState.LOST, lifecycle.state());
        assertEquals(new LifecycleRevision(2), lifecycle.revision());
        assertEquals(
                new ReconciliationGeneration(1),
                lifecycle.lastReconciledGeneration()
        );
        assertEquals(
                com.alechilles.alecstamework.companion.identity.CompanionAlias
                        .State.RETIRED,
                storedAlias(ALIAS_A).state()
        );
        assertNull(storedCurrentAlias());
        assertFalse(storedSnapshot(source.snapshotId()).current());
        CompanionSnapshot lost = storedCurrentSnapshot(
                new SnapshotKind("lost")
        );
        assertEquals(2, lost.payloadVersion());
        assertEquals(payload, lost.payloadJson());
        assertEquals(source.payloadHash(), lost.payloadHash());

        OperationWorkflowResult replay = submitAsync(
                5,
                "profile-import-recovery-replay",
                recovery
        ).get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, replay.events().size());
        assertEquals(
                CompanionProfileMutationOutcome.Status.UNCHANGED,
                CompanionProfileMutationEventCodec.decode(
                        replay.events().getFirst().payloadVersion(),
                        replay.events().getFirst().payloadJson()
                ).status()
        );
        assertEquals(lost, storedCurrentSnapshot(new SnapshotKind("lost")));
    }

    @Test
    void reconcilesImportedUnresolvedLifecycleAndModernAliasAtomically()
            throws Exception {
        CompanionProfileMutation.Create imported =
                new CompanionProfileMutation.Create(
                        identity(0, "Imported", -9_000),
                        new CompanionLifecycle(
                                PROFILE,
                                OWNER_A,
                                LifecycleState.UNRESOLVED,
                                LifecycleLocation.unresolved(),
                                LifecycleRevision.INITIAL,
                                null,
                                -9_000,
                                ReconciliationGeneration.INITIAL,
                                null,
                                "owner-world"
                        ),
                        List.of(),
                        -9_000
                );
        submit(1, "profile-imported", imported);
        insertCurrentAlias(ALIAS_A);
        CompanionProfileMutation.ReconcileLoaded reconciliation =
                new CompanionProfileMutation.ReconcileLoaded(
                        PROFILE,
                        LifecycleRevision.INITIAL,
                        ReconciliationGeneration.INITIAL,
                        ALIAS_A,
                        ALIAS_B,
                        "loaded-world",
                        -8_000
                );

        OperationWorkflowResult result = submitAsync(
                2,
                "profile-reconcile-loaded",
                reconciliation
        ).get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(3, result.events().size());
        CompanionProfileMutationOutcome outcome =
                CompanionProfileMutationEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );
        assertEquals(
                CompanionProfileMutationOutcome.Status.UPDATED,
                outcome.status()
        );
        CompanionProfileProjectionChange change =
                CompanionProfileProjectionChangeCodec.decode(
                        result.events().get(1).payloadVersion(),
                        result.events().get(1).payloadJson()
                );
        assertEquals(CompanionProfileProjectionChange.Source.ALIAS, change.source());
        assertEquals(1, change.sourceRevision());

        CompanionLifecycle lifecycle = storedLifecycle(PROFILE);
        assertEquals(LifecycleState.ACTIVE, lifecycle.state());
        assertEquals(
                LifecycleLocation.liveEntity(ALIAS_B.toString(), "loaded-world"),
                lifecycle.location()
        );
        assertEquals(new LifecycleRevision(1), lifecycle.revision());
        assertEquals(
                new ReconciliationGeneration(1),
                lifecycle.lastReconciledGeneration()
        );
        assertEquals("owner-world", lifecycle.ownerWorldKey());
        assertEquals(
                com.alechilles.alecstamework.companion.identity.CompanionAlias.State.RETIRED,
                storedAlias(ALIAS_A).state()
        );
        assertEquals(
                com.alechilles.alecstamework.companion.identity.CompanionAlias.State.CURRENT,
                storedAlias(ALIAS_B).state()
        );
        assertEquals(1, storedAlias(ALIAS_B).generation());

        OperationWorkflowResult replay = submitAsync(
                2,
                "profile-reconcile-loaded",
                reconciliation
        ).get(10, TimeUnit.SECONDS);
        assertEquals(result.events(), replay.events());
        assertEquals(lifecycle, storedLifecycle(PROFILE));

        CompanionProfileMutation.ReconcileLoaded moved =
                new CompanionProfileMutation.ReconcileLoaded(
                        PROFILE,
                        new LifecycleRevision(1),
                        new ReconciliationGeneration(1),
                        ALIAS_B,
                        ALIAS_B,
                        "temporary-world",
                        -7_000
                );
        OperationWorkflowResult movedResult = submitAsync(
                3,
                "profile-reconcile-runtime-world",
                moved
        ).get(10, TimeUnit.SECONDS);
        assertEquals(
                CompanionProfileMutationOutcome.Status.UPDATED,
                CompanionProfileMutationEventCodec.decode(
                        movedResult.events().getFirst().payloadVersion(),
                        movedResult.events().getFirst().payloadJson()
                ).status()
        );
        CompanionLifecycle movedLifecycle = storedLifecycle(PROFILE);
        assertEquals(
                LifecycleLocation.liveEntity(
                        ALIAS_B.toString(), "temporary-world"
                ),
                movedLifecycle.location()
        );
        assertEquals(new LifecycleRevision(2), movedLifecycle.revision());
        assertEquals(
                new ReconciliationGeneration(2),
                movedLifecycle.lastReconciledGeneration()
        );

        OperationWorkflowResult stale = submitAsync(
                4,
                "profile-reconcile-stale-runtime-world",
                moved
        ).get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, stale.status());
        assertEquals(1, stale.events().size());
        assertEquals(
                CompanionProfileMutationOutcome.Status.UNCHANGED,
                CompanionProfileMutationEventCodec.decode(
                        stale.events().getFirst().payloadVersion(),
                        stale.events().getFirst().payloadJson()
                ).status()
        );
        assertEquals(movedLifecycle, storedLifecycle(PROFILE));
    }

    @Test
    void concurrentAdoptionsOfOneAliasLeaveExactlyOneCompleteProfile()
            throws Exception {
        CompanionProfileMutation.AdoptLive first = adoption(
                PROFILE,
                ALIAS_A,
                OWNER_A,
                "First",
                List.of(link(PROFILE, TOOL_A, -9_000))
        );
        CompanionProfileMutation.AdoptLive second = adoption(
                PROFILE_B,
                ALIAS_A,
                OWNER_B,
                "Second",
                List.of(link(PROFILE_B, TOOL_B, -9_000))
        );

        CompletableFuture<OperationWorkflowResult> firstResult = submitAsync(
                1,
                "profile-adopt-first",
                first
        );
        CompletableFuture<OperationWorkflowResult> secondResult = submitAsync(
                2,
                "profile-adopt-second",
                second
        );
        CompanionProfileMutationOutcome firstOutcome =
                outcome(firstResult.get(10, TimeUnit.SECONDS));
        CompanionProfileMutationOutcome secondOutcome =
                outcome(secondResult.get(10, TimeUnit.SECONDS));

        assertTrue(
                firstOutcome.status() == CompanionProfileMutationOutcome.Status.CREATED
                        ^ secondOutcome.status()
                        == CompanionProfileMutationOutcome.Status.CREATED
        );
        assertTrue(
                firstOutcome.status() == CompanionProfileMutationOutcome.Status.CONFLICT
                        ^ secondOutcome.status()
                        == CompanionProfileMutationOutcome.Status.CONFLICT
        );
        CompanionProfileMutation.AdoptLive winner =
                firstOutcome.status() == CompanionProfileMutationOutcome.Status.CREATED
                        ? first
                        : second;
        CompanionProfileMutation.AdoptLive loser = winner == first ? second : first;
        assertEquals(winner.profileId(), storedAlias(ALIAS_A).profileId());
        assertEquals(winner.identity(), storedIdentity(winner.profileId()));
        assertEquals(winner.initialLifecycle(), storedLifecycle(winner.profileId()));
        assertTrue(profileExists(winner.profileId()));
        assertFalse(profileExists(loser.profileId()));
    }

    private CompanionProfileMutationOutcome submit(
            int operationNumber,
            String idempotencyKey,
            CompanionProfileMutation mutation
    ) throws Exception {
        return outcome(submitAsync(
                operationNumber,
                idempotencyKey,
                mutation
        ).get(10, TimeUnit.SECONDS));
    }

    private CompletableFuture<OperationWorkflowResult> submitAsync(
            int operationNumber,
            String idempotencyKey,
            CompanionProfileMutation mutation
    ) {
        return operations.submit(
                OperationId.parse(String.format(
                        "40000000-0000-0000-0000-%012d",
                        operationNumber
                )),
                new IdempotencyKey(idempotencyKey),
                mutation
        ).completion().toCompletableFuture();
    }

    private CompanionProfileMutationOutcome outcome(OperationWorkflowResult result) {
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(OperationPhase.PUBLISHED, result.operation().phase());
        assertEquals(
                SqliteCompanionProfileOperations.EVENT_TYPE,
                result.events().getFirst().eventType()
        );
        CompanionProfileMutationOutcome outcome =
                CompanionProfileMutationEventCodec.decode(
                result.events().getFirst().payloadVersion(),
                result.events().getFirst().payloadJson()
        );
        if (outcome.status() == CompanionProfileMutationOutcome.Status.CREATED
                || outcome.status() == CompanionProfileMutationOutcome.Status.UPDATED) {
            assertEquals(
                    outcome.status()
                            == CompanionProfileMutationOutcome.Status.CREATED
                            ? 3
                            : 2,
                    result.events().size()
            );
            CompanionProfileProjectionChange change =
                    CompanionProfileProjectionChangeCodec.decode(
                            result.events().get(1).payloadVersion(),
                            result.events().get(1).payloadJson()
                    );
            assertEquals(outcome.profileId(), change.profileId());
            assertEquals(outcome.metadataRevision(), change.sourceRevision());
            assertEquals(
                    CompanionProfileProjectionChange.Source.METADATA,
                    change.source()
            );
        } else {
            assertEquals(1, result.events().size());
        }
        return outcome;
    }

    private CompanionProfileMutation.AdoptLive adoption(
            ProfileId profileId,
            NpcAlias alias,
            OwnerId ownerId,
            String name,
            List<CompanionToolLink> links
    ) {
        return new CompanionProfileMutation.AdoptLive(
                identity(profileId, 0, name, -9_000),
                alias,
                ownerId,
                "world",
                links,
                -9_000
        );
    }

    private CompanionProfileMutation.Create create(
            String name,
            List<CompanionToolLink> links
    ) {
        return new CompanionProfileMutation.Create(
                identity(0, name, -9_000),
                new CompanionLifecycle(
                        PROFILE,
                        OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        -9_000,
                        ReconciliationGeneration.INITIAL,
                        null
                ),
                links,
                -9_000
        );
    }

    private CompanionIdentity identity(long revision, String name, long updatedAtMs) {
        return identity(PROFILE, revision, name, updatedAtMs);
    }

    private CompanionIdentity identity(
            ProfileId profileId,
            long revision,
            String name,
            long updatedAtMs
    ) {
        String metadata = "{\"source\":\"test\"}";
        return new CompanionIdentity(
                profileId,
                name,
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -10_000,
                updatedAtMs,
                updatedAtMs,
                revision
        );
    }

    private CompanionToolLink link(UUID toolId, long updatedAtMs) {
        return link(PROFILE, toolId, updatedAtMs);
    }

    private CompanionToolLink link(
            ProfileId profileId,
            UUID toolId,
            long updatedAtMs
    ) {
        return new CompanionToolLink(
                profileId,
                toolId,
                "command",
                -9_000,
                updatedAtMs
        );
    }

    private CompanionIdentity storedIdentity() throws Exception {
        return storedIdentity(PROFILE);
    }

    private CompanionIdentity storedIdentity(ProfileId profileId) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .findProfile(profileId)
                    .orElseThrow();
        }
    }

    private boolean profileExists(ProfileId profileId) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .findProfile(profileId)
                    .isPresent();
        }
    }

    private com.alechilles.alecstamework.companion.identity.CompanionAlias storedAlias(
            NpcAlias alias
    ) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(alias)
                    .orElseThrow();
        }
    }

    private boolean aliasExists(NpcAlias alias) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(alias)
                    .isPresent();
        }
    }

    private void insertCurrentAlias(NpcAlias alias) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO companion_alias(
                         npc_uuid, profile_id, alias_generation, alias_state,
                         lease_operation_id, mapped_at_ms, retired_at_ms
                     ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                     """)) {
            statement.setString(1, alias.toString());
            statement.setString(2, PROFILE.toString());
            statement.setLong(3, -9_000);
            statement.executeUpdate();
        }
    }

    private void insertRetiredAlias(NpcAlias alias, int generation)
            throws Exception {
        try (Connection connection = connections.openWriterConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO companion_alias(
                         npc_uuid, profile_id, alias_generation, alias_state,
                         lease_operation_id, mapped_at_ms, retired_at_ms
                     ) VALUES (?, ?, ?, 'RETIRED', NULL, ?, ?)
                     """)) {
            statement.setString(1, alias.toString());
            statement.setString(2, PROFILE.toString());
            statement.setInt(3, generation);
            statement.setLong(4, -9_500);
            statement.setLong(5, -9_000);
            statement.executeUpdate();
        }
    }

    private void insertSnapshot(CompanionSnapshot snapshot) throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            assertTrue(new SqliteCompanionSnapshotStore(connection)
                    .replaceCurrent(snapshot)
                    .applied());
        }
    }

    private com.alechilles.alecstamework.companion.identity.CompanionAlias
    storedCurrentAlias() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .findCurrentAlias(PROFILE)
                    .orElse(null);
        }
    }

    private CompanionSnapshot storedSnapshot(SnapshotId snapshotId)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findById(snapshotId)
                    .orElseThrow();
        }
    }

    private CompanionSnapshot storedCurrentSnapshot(SnapshotKind kind)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findCurrent(PROFILE, kind)
                    .orElseThrow();
        }
    }

    private CompanionSnapshot storedCurrentSnapshotOrNull(SnapshotKind kind)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findCurrent(PROFILE, kind)
                    .orElse(null);
        }
    }

    private CompanionLifecycle storedLifecycle(ProfileId profileId) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(profileId)
                    .orElseThrow();
        }
    }

    private List<UUID> storedToolIds() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionToolLinkStore(connection)
                    .findByProfile(PROFILE)
                    .stream()
                    .map(CompanionToolLink::toolId)
                    .toList();
        }
    }

    private static final class RevisionConsumer implements ProjectionConsumer {
        private final Map<String, Long> revisions = new HashMap<>();
        private int appliedEvents;

        @Override
        public ProjectionConsumerId consumerId() {
            return new ProjectionConsumerId("companion_profile_view");
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            if (!event.eventType().equals(SqliteCompanionProfileOperations.EVENT_TYPE)) {
                return ProjectionApplyOutcome.IRRELEVANT;
            }
            long current = revisions.getOrDefault(event.aggregateId(), -1L);
            if (current >= event.aggregateRevision()) {
                return ProjectionApplyOutcome.ALREADY_APPLIED;
            }
            CompanionProfileMutationEventCodec.decode(
                    event.payloadVersion(),
                    event.payloadJson()
            );
            revisions.put(event.aggregateId(), event.aggregateRevision());
            appliedEvents++;
            return ProjectionApplyOutcome.APPLIED;
        }
    }
}
