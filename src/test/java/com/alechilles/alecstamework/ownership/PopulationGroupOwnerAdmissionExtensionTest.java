package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.api.PopulationGroupLimitChangedEvent;
import com.alechilles.alecstamework.api.PopulationGroupMembershipChangedEvent;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupCountEvidenceRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationGroupOwnerAdmissionExtensionTest {
    @TempDir
    Path tempDir;

    @Test
    void centralOwnerAuthorityReservesGroupHeadroomForEveryPositiveAdmission() throws Exception {
        try (Harness harness = harness()) {
            UUID owner = UUID.randomUUID();
            OwnerPopulationPreparationResult first = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);
            OwnerPopulationPreparationResult second = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);

            assertTrue(first.allowed());
            assertFalse(second.allowed());
            assertEquals("population-group-owned-limit", second.reason());
            assertTrue(harness.coordinator().claimForApply(
                    first.preparedAdmission(), 1L, ClaimProviderGeneration.NONE));
            assertTrue(harness.coordinator().commitAsync(first.preparedAdmission())
                    .get(2, TimeUnit.SECONDS).committed());
            assertFalse(harness.coordinator().commitAsync(first.preparedAdmission())
                    .get(2, TimeUnit.SECONDS).committed());

            assertEquals(List.of("hydragon:soulbound_mini"), harness.groups()
                    .findClassification(first.preparedAdmission().plan().transition().profileId())
                    .groupIds());
            PopulationGroupRepository.Counts counts = harness.groups().count(
                    owner, "hydragon:soulbound_mini",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null);
            assertEquals(1, counts.committedOwned());
            assertEquals(1, counts.committedActive());
            assertEquals(1, harness.events().size());
            PopulationGroupMembershipChangedEvent event =
                    (PopulationGroupMembershipChangedEvent) harness.events().getFirst();
            assertEquals(Set.of(), event.oldGroupIds());
            assertEquals(Set.of("hydragon:soulbound_mini"), event.newGroupIds());
            assertFalse(event.recovered());
        }
    }

    @Test
    void limitPublicationIsIdempotentAndReportsOnlyChangedDefinitions() throws Exception {
        try (Harness harness = harness()) {
            PopulationGroupRegistry next = new PopulationGroupRegistry();
            assertTrue(next.replace(List.of(group(2, 1)), 2L).applied());

            harness.extension().publishLimitChanges(
                    harness.registry().snapshot(), next.snapshot(), false);
            harness.extension().publishLimitChanges(
                    harness.registry().snapshot(), next.snapshot(), false);

            assertTrue(harness.queue().awaitIdle(2_000L));
            restartExtension(harness).publishLimitChanges(
                    harness.registry().snapshot(), next.snapshot(), true);
            assertTrue(harness.queue().awaitIdle(2_000L));
            assertEquals(1, harness.events().size());
            PopulationGroupLimitChangedEvent event =
                    (PopulationGroupLimitChangedEvent) harness.events().getFirst();
            assertEquals("hydragon:soulbound_mini", event.groupId());
            assertEquals(1L, event.oldMaxOwned());
            assertEquals(2L, event.newMaxOwned());
        }
    }

    @Test
    void listenerFailureIsIsolatedBehindDurableEventFence() throws Exception {
        try (Harness harness = harness()) {
            PopulationGroupRegistry next = new PopulationGroupRegistry();
            assertTrue(next.replace(List.of(group(2, 1)), 2L).applied());
            PopulationGroupOwnerAdmissionExtension failing = restartExtension(
                    harness, event -> { throw new IllegalStateException("listener-failed"); });

            failing.publishLimitChanges(harness.registry().snapshot(), next.snapshot(), false);
            assertTrue(harness.queue().awaitIdle(2_000L));
            restartExtension(harness).publishLimitChanges(
                    harness.registry().snapshot(), next.snapshot(), true);
            assertTrue(harness.queue().awaitIdle(2_000L));

            assertTrue(harness.events().isEmpty());
            assertEquals(1L, scalar(harness.connections(),
                    "SELECT COUNT(*) FROM companion_population_group_event_receipts"));
        }
    }

    @Test
    void canceledCentralAdmissionReleasesItsGroupReservationExactlyOnce() throws Exception {
        try (Harness harness = harness()) {
            UUID owner = UUID.randomUUID();
            OwnerPopulationPreparationResult prepared = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(prepared.allowed());
            assertTrue(harness.coordinator().cancelAsync(
                    prepared.preparedAdmission(), "test-cancel").get(2, TimeUnit.SECONDS));
            assertTrue(harness.coordinator().cancelAsync(
                    prepared.preparedAdmission(), "test-cancel-replay").get(2, TimeUnit.SECONDS));
            assertEquals(0, harness.groups().count(owner, "hydragon:soulbound_mini",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).pendingOwned());

            OwnerPopulationPreparationResult retry = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(retry.allowed());
            assertTrue(harness.coordinator().cancelAsync(
                    retry.preparedAdmission(), "test-cleanup").get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentReservationsAtMaxOneProduceOneWinner() throws Exception {
        try (Harness harness = harness()) {
            UUID owner = UUID.randomUUID();
            CompletableFuture<OwnerPopulationPreparationResult> first =
                    CompletableFuture.supplyAsync(() -> harness.coordinator().prepareAsync(
                            plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini")).join());
            CompletableFuture<OwnerPopulationPreparationResult> second =
                    CompletableFuture.supplyAsync(() -> harness.coordinator().prepareAsync(
                            plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini")).join());

            OwnerPopulationPreparationResult left = first.get(2, TimeUnit.SECONDS);
            OwnerPopulationPreparationResult right = second.get(2, TimeUnit.SECONDS);
            assertEquals(1, (left.allowed() ? 1 : 0) + (right.allowed() ? 1 : 0));
            OwnerPopulationPreparationResult winner = left.allowed() ? left : right;
            assertTrue(harness.coordinator().cancelAsync(
                    winner.preparedAdmission(), "test-cleanup").get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void roleChangeDebitsOldGroupAndCreditsNewGroupAtomically() throws Exception {
        try (Harness harness = harness(List.of(
                group("HyDragon_Soulbound_Mini", "hydragon:soulbound_mini",
                        "Tamed_Wyvern_Mini", 1, 1),
                group("HyDragon_Soulbound_Scout", "hydragon:soulbound_scout",
                        "Tamed_Wyvern_Scout", 1, 1)))) {
            UUID owner = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            OwnerPopulationPreparationResult created = harness.coordinator().prepareAsync(
                    plan(profileId, owner, "Tamed_Wyvern_Mini")).get(2, TimeUnit.SECONDS);
            assertTrue(created.allowed());
            assertTrue(harness.coordinator().claimForApply(
                    created.preparedAdmission(), 1L, ClaimProviderGeneration.NONE));
            assertTrue(harness.coordinator().commitAsync(created.preparedAdmission())
                    .get(2, TimeUnit.SECONDS).committed());

            CompanionPopulationStateRecord baseline = harness.population().loadAllStates().stream()
                    .filter(state -> state.profileId().equals(profileId)).findFirst().orElseThrow();
            OwnerPopulationPreparationResult changed = harness.coordinator().prepareAsync(
                    roleChangePlan(baseline, owner, "Tamed_Wyvern_Mini", "Tamed_Wyvern_Scout"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(changed.allowed());
            assertTrue(harness.coordinator().claimForApply(
                    changed.preparedAdmission(), 1L, ClaimProviderGeneration.NONE));
            assertTrue(harness.coordinator().commitAsync(changed.preparedAdmission())
                    .get(2, TimeUnit.SECONDS).committed());

            assertEquals(List.of("hydragon:soulbound_scout"),
                    harness.groups().findClassification(profileId).groupIds());
            assertEquals(0, harness.groups().count(owner, "hydragon:soulbound_mini",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).committedOwned());
            assertEquals(1, harness.groups().count(owner, "hydragon:soulbound_scout",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).committedOwned());
        }
    }

    /** Regression: a post-change live snapshot must not replace the durable old classification. */
    @Test
    void roleChangeUsesPersistedOldClassificationWhenPlanAlreadyObservesTargetRole()
            throws Exception {
        try (Harness harness = harness(List.of(
                group("Group_A", "test:a", "Role_A", 1, 1),
                group("Group_B", "test:b", "Role_B", 1, 1)))) {
            UUID owner = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            OwnerPopulationPreparationResult created = harness.coordinator().prepareAsync(
                    plan(profileId, owner, "Role_A")).get(2, TimeUnit.SECONDS);
            assertTrue(created.allowed());
            assertTrue(harness.coordinator().claimForApply(
                    created.preparedAdmission(), 1L, ClaimProviderGeneration.NONE));
            assertTrue(harness.coordinator().commitAsync(created.preparedAdmission())
                    .get(2, TimeUnit.SECONDS).committed());

            CompanionPopulationStateRecord baseline = harness.population().loadAllStates().stream()
                    .filter(state -> state.profileId().equals(profileId)).findFirst().orElseThrow();
            OwnerPopulationPreparationResult changed = harness.coordinator().prepareAsync(
                    roleChangePlan(baseline, owner, "Role_B", "Role_B"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(changed.allowed());
            assertTrue(harness.coordinator().claimForApply(
                    changed.preparedAdmission(), 1L, ClaimProviderGeneration.NONE));
            assertTrue(harness.coordinator().commitAsync(changed.preparedAdmission())
                    .get(2, TimeUnit.SECONDS).committed());

            assertEquals(0, harness.groups().count(owner, "test:a",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).committedOwned());
            assertEquals(1, harness.groups().count(owner, "test:b",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).committedOwned());
        }
    }

    @Test
    void configPersistenceUnavailableDeniesWithoutLeavingPendingHeadroom() throws Exception {
        try (Harness harness = harness()) {
            UUID owner = UUID.randomUUID();
            harness.health().markDegraded("test-config-persistence-unavailable");

            OwnerPopulationPreparationResult result = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);

            assertFalse(result.allowed());
            assertEquals(0, harness.groups().count(owner, "hydragon:soulbound_mini",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).pendingOwned());
        }
    }

    @Test
    void concurrentRestartRecoveryConvergesApplyingCommitAndEmitsLogicallyOnce()
            throws Exception {
        try (Harness harness = harness()) {
            UUID owner = UUID.randomUUID();
            OwnerPopulationPreparationResult prepared = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(prepared.allowed());
            assertTrue(harness.coordinator().claimForApply(
                    prepared.preparedAdmission(), 1L, ClaimProviderGeneration.NONE));
            assertTrue(harness.coordinator().commitAsync(prepared.preparedAdmission())
                    .get(2, TimeUnit.SECONDS).committed());

            String groupOperationId = groupOperationId(prepared.preparedAdmission().operationId());
            forceGroupState(harness.connections(), groupOperationId, "APPLYING", "RESERVED");
            forceProfileRole(harness.connections(),
                    prepared.preparedAdmission().plan().transition().profileId(),
                    "Tamed_Wyvern_Mini");
            deleteEventReceipt(harness.connections(), groupOperationId);
            harness.events().clear();
            PopulationGroupOwnerAdmissionExtension restarted = restartExtension(harness);

            CompletableFuture<PopulationGroupOwnerAdmissionExtension.RecoveryReport> first =
                    restarted.recover();
            CompletableFuture<PopulationGroupOwnerAdmissionExtension.RecoveryReport> second =
                    restarted.recover();
            assertTrue(first.get(2, TimeUnit.SECONDS).ready());
            assertTrue(second.get(2, TimeUnit.SECONDS).ready());
            assertEquals("COMMITTED", operationState(harness.connections(), groupOperationId));
            assertEquals(1, harness.events().size());
            assertTrue(((PopulationGroupMembershipChangedEvent) harness.events().getFirst())
                    .recovered());

            PopulationGroupOwnerAdmissionExtension secondRestart = restartExtension(harness);
            assertTrue(secondRestart.recover().get(2, TimeUnit.SECONDS).ready());
            assertEquals(1, harness.events().size());
        }
    }

    @Test
    void restartRecoveryReleasesQuarantinedRollbackExactlyOnce() throws Exception {
        try (Harness harness = harness()) {
            UUID owner = UUID.randomUUID();
            OwnerPopulationPreparationResult prepared = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(prepared.allowed());
            String ownerOperationId = prepared.preparedAdmission().operationId().toString();
            String groupOperationId = groupOperationId(prepared.preparedAdmission().operationId());
            forceOwnerState(harness.connections(), ownerOperationId, "FAILED");
            forceGroupState(harness.connections(), groupOperationId, "QUARANTINED", "QUARANTINED");
            forceProfileRole(harness.connections(),
                    prepared.preparedAdmission().plan().transition().profileId(),
                    "Tamed_Wyvern_Mini");

            PopulationGroupOwnerAdmissionExtension restarted = restartExtension(harness);
            assertTrue(restarted.recover().get(2, TimeUnit.SECONDS).ready());
            assertEquals("FAILED", operationState(harness.connections(), groupOperationId));
            assertEquals(0, harness.groups().count(owner, "hydragon:soulbound_mini",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).pendingOwned());
            assertTrue(restarted.recover().get(2, TimeUnit.SECONDS).ready());
            assertEquals("FAILED", operationState(harness.connections(), groupOperationId));
        }
    }

    @Test
    void emptyGroupPolicyDoesNotDisableOrdinaryTameworkOwnership() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("empty-population-groups.sqlite"));
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.commit();
        }
        PersistenceHealthService health = new PersistenceHealthService();
        try (PersistenceWriteQueue queue = new PersistenceWriteQueue(connections, health, null)) {
            CompanionPopulationRepository population = new CompanionPopulationRepository(connections, queue);
            PopulationGroupRepository groups = new PopulationGroupRepository(connections, queue);
            OwnerPopulationIndex index = new OwnerPopulationIndex();
            index.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.READY);
            OwnerPopulationAdmissionCoordinator coordinator =
                    new OwnerPopulationAdmissionCoordinator(index, population, health);
            PopulationGroupRegistry registry = new PopulationGroupRegistry();
            assertTrue(registry.replace(List.of(), 1L).applied());
            coordinator.installPopulationGroups(new PopulationGroupOwnerAdmissionExtension(
                    coordinator, registry, groups, new NpcProfileRepository(connections, queue)));

            OwnerPopulationPreparationResult result = coordinator.prepareAsync(
                    plan(UUID.randomUUID().toString(), UUID.randomUUID(), "Ordinary_Companion"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(result.allowed());
            assertTrue(coordinator.cancelAsync(result.preparedAdmission(), "test-cleanup")
                    .get(2, TimeUnit.SECONDS));
        }
    }

    /** Regression: schema-v8 group configs must not globally fail on older role-less profiles. */
    @Test
    void rolelessHistoricalProfileDoesNotDisablePopulationGroupRecovery() throws Exception {
        try (Harness harness = harness()) {
            String profileId = UUID.randomUUID().toString();
            insertHistoricalProfile(harness.connections(), profileId, UUID.randomUUID());

            PopulationGroupOwnerAdmissionExtension.RecoveryReport report =
                    restartExtension(harness).recover().get(2, TimeUnit.SECONDS);

            assertTrue(report.ready());
            assertEquals(0, report.failed());
            PopulationGroupClassificationRecord classification =
                    harness.groups().findClassification(profileId);
            assertEquals(PopulationGroupClassificationRecord.Status.UNRESOLVED,
                    classification.status());
            assertEquals(List.of(), classification.groupIds());
        }
    }

    /** A group classification is durable authority when older profile metadata lacks its role. */
    @Test
    void durableClassificationSurvivesMissingProfileRoleMetadata() throws Exception {
        try (Harness harness = harness()) {
            String profileId = UUID.randomUUID().toString();
            UUID owner = UUID.randomUUID();
            insertHistoricalProfile(harness.connections(), profileId, owner);
            long now = System.currentTimeMillis();
            var submission = harness.groups().replaceClassificationAsync(
                    new PopulationGroupRepository.ClassificationMutation(null,
                            new PopulationGroupClassificationRecord(
                                    profileId, "Tamed_Wyvern_Mini",
                                    List.of("hydragon:soulbound_mini"), 1L,
                                    PopulationGroupClassificationRecord.Status.RESOLVED,
                                    "owner_population_admission", now, now)));
            assertTrue(submission.completion().get(2, TimeUnit.SECONDS).isCommitted());

            PopulationGroupOwnerAdmissionExtension.RecoveryReport report =
                    restartExtension(harness).recover().get(2, TimeUnit.SECONDS);

            assertTrue(report.ready());
            PopulationGroupClassificationRecord classification =
                    harness.groups().findClassification(profileId);
            assertEquals("Tamed_Wyvern_Mini", classification.roleId());
            assertEquals(List.of("hydragon:soulbound_mini"), classification.groupIds());
            assertEquals(PopulationGroupClassificationRecord.Status.RESOLVED,
                    classification.status());
            assertEquals(1, harness.groups().count(
                    owner, "hydragon:soulbound_mini",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null).committedOwned());
        }
    }

    @Test
    void positiveAdmissionWithoutTargetRoleStillFailsClosed() throws Exception {
        try (Harness harness = harness()) {
            long now = System.currentTimeMillis();
            String profileId = UUID.randomUUID().toString();
            UUID npc = UUID.randomUUID();
            CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                    profileId, npc, null, "default", "default",
                    CompanionLifecycleState.ACTIVE.name(), "default", 0, 0,
                    0L, "test", now, now);
            OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                    profileId, OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION, null, null,
                    UUID.randomUUID(), "default", CompanionLifecycleState.ACTIVE,
                    OwnerPopulationOperation.NEW_OWNERSHIP, OwnerPopulationLimitScope.GLOBAL,
                    100, false);
            OwnerPopulationAdmissionPlan planWithoutRole = new OwnerPopulationAdmissionPlan(
                    transition, baseline, npc, "default", 0, 0, "test",
                    "{}", "{}", "{}", 1L, ClaimProviderGeneration.NONE);

            OwnerPopulationPreparationResult result = harness.coordinator().prepareAsync(
                    planWithoutRole).get(2, TimeUnit.SECONDS);

            assertFalse(result.allowed());
            assertEquals("population-group-target-role-unresolved", result.reason());
        }
    }

    private Harness harness() throws Exception {
        return harness(List.of(group()));
    }

    private Harness harness(List<TwPopulationGroupConfig> definitions) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("population-groups.sqlite"));
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.commit();
        }
        PersistenceHealthService health = new PersistenceHealthService();
        PersistenceWriteQueue queue = new PersistenceWriteQueue(connections, health, null);
        CompanionPopulationRepository population = new CompanionPopulationRepository(connections, queue);
        PopulationGroupRepository groups = new PopulationGroupRepository(connections, queue);
        NpcProfileRepository profiles = new NpcProfileRepository(connections, queue);
        OwnerPopulationIndex index = new OwnerPopulationIndex();
        index.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.READY);
        OwnerPopulationAdmissionCoordinator coordinator =
                new OwnerPopulationAdmissionCoordinator(index, population, health);
        PopulationGroupRegistry registry = new PopulationGroupRegistry();
        assertTrue(registry.replace(definitions, 1L).applied());
        List<TameworkEvent> events = new ArrayList<>();
        PopulationGroupOwnerAdmissionExtension extension =
                new PopulationGroupOwnerAdmissionExtension(
                        coordinator, registry, groups, profiles, events::add);
        assertTrue(extension.recover().get(2, TimeUnit.SECONDS).ready());
        coordinator.installPopulationGroups(extension);
        return new Harness(connections, health, queue, population, coordinator, groups,
                profiles, registry, extension, events);
    }

    private static OwnerPopulationAdmissionPlan plan(String profileId, UUID owner, String role) {
        long now = System.currentTimeMillis();
        UUID npc = UUID.randomUUID();
        CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                profileId, npc, null, "default", "default",
                CompanionLifecycleState.ACTIVE.name(), "default", 0, 0,
                0L, "test", now, now);
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profileId, OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION, null, null,
                owner, "default", CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.NEW_OWNERSHIP, OwnerPopulationLimitScope.GLOBAL,
                100, false);
        return new OwnerPopulationAdmissionPlan(
                transition, baseline, npc, "default", 0, 0, "test",
                "{}", "{}", "{}", 1L, ClaimProviderGeneration.NONE,
                new PopulationGroupRoleContext(null, role));
    }

    private static OwnerPopulationAdmissionPlan roleChangePlan(
            CompanionPopulationStateRecord baseline,
            UUID owner,
            String oldRole,
            String newRole) {
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                baseline.profileId(), baseline.revision(), owner, baseline.ownershipWorldName(),
                owner, baseline.ownershipWorldName(), CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.LIFECYCLE_CHANGE, OwnerPopulationLimitScope.GLOBAL,
                100, false);
        return new OwnerPopulationAdmissionPlan(
                transition, baseline, baseline.currentNpcUuid(), baseline.physicalWorldName(),
                baseline.physicalChunkX(), baseline.physicalChunkZ(), "test-role-change",
                "{}", "{}", "{}", 1L, ClaimProviderGeneration.NONE,
                new PopulationGroupRoleContext(oldRole, newRole));
    }

    private static TwPopulationGroupConfig group() throws Exception {
        return group(1, 1);
    }

    private static TwPopulationGroupConfig group(int maxOwned, int maxActive) throws Exception {
        return group("HyDragon_Soulbound_Mini", "hydragon:soulbound_mini",
                "Tamed_Wyvern_Mini", maxOwned, maxActive);
    }

    private static TwPopulationGroupConfig group(
            String id, String groupId, String role, int maxOwned, int maxActive) throws Exception {
        var constructor = TwPopulationGroupConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwPopulationGroupConfig config = constructor.newInstance();
        set(config, "id", id);
        set(config, "groupId", groupId);
        set(config, "priority", 100);
        set(config, "roleIds", new String[] {role});
        Object limits = field(config, "limits");
        set(limits, "maxOwnedPerOwner", maxOwned);
        set(limits, "maxActivePerOwner", maxActive);
        set(limits, "scope", PopulationGroupScope.GLOBAL);
        return config;
    }

    private static PopulationGroupOwnerAdmissionExtension restartExtension(Harness harness) {
        return restartExtension(harness, harness.events()::add);
    }

    private static PopulationGroupOwnerAdmissionExtension restartExtension(
            Harness harness,
            com.alechilles.alecstamework.ownership.groups.PopulationGroupEventSink events) {
        OwnerPopulationIndex index = new OwnerPopulationIndex();
        index.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.READY);
        OwnerPopulationAdmissionCoordinator coordinator = new OwnerPopulationAdmissionCoordinator(
                index, harness.population(), harness.health());
        return new PopulationGroupOwnerAdmissionExtension(
                coordinator, harness.registry(), harness.groups(), harness.profiles(),
                events);
    }

    private static String groupOperationId(UUID ownerOperationId) {
        return UUID.nameUUIDFromBytes((ownerOperationId + ":groups")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static void forceOwnerState(
            SqliteConnectionManager connections, String operationId, String state) throws Exception {
        execute(connections, "UPDATE companion_population_operations "
                + "SET state = ?, completed_at_ms = 1 WHERE operation_id = ?", state, operationId);
    }

    private static void forceGroupState(
            SqliteConnectionManager connections,
            String operationId,
            String state,
            String evidenceState) throws Exception {
        execute(connections, "UPDATE companion_population_group_operations "
                + "SET state = ?, completed_at_ms = 0 WHERE operation_id = ?", state, operationId);
        execute(connections, "UPDATE companion_population_group_count_evidence "
                + "SET state = ? WHERE operation_id = ?", evidenceState, operationId);
    }

    private static void forceProfileRole(
            SqliteConnectionManager connections, String profileId, String role) throws Exception {
        execute(connections, "UPDATE npc_profiles SET role_id = ? WHERE profile_id = ?",
                role, profileId);
    }

    private static void deleteEventReceipt(
            SqliteConnectionManager connections, String eventId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM companion_population_group_event_receipts WHERE event_id = ?")) {
            statement.setString(1, eventId);
            statement.executeUpdate();
        }
    }

    private static void insertHistoricalProfile(
            SqliteConnectionManager connections, String profileId, UUID owner) throws Exception {
        long now = System.currentTimeMillis();
        try (Connection connection = connections.openConnection();
             PreparedStatement profile = connection.prepareStatement("""
                     INSERT INTO npc_profiles (
                         profile_id, current_npc_uuid, owner_uuid, last_world_name,
                         created_at_ms, updated_at_ms, last_active_at_ms
                     ) VALUES (?, ?, ?, 'default', ?, ?, ?)
                     """);
             PreparedStatement population = connection.prepareStatement("""
                     INSERT INTO companion_population_state (
                         profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                         physical_chunk_x, physical_chunk_z, revision, source,
                         created_at_ms, updated_at_ms
                     ) VALUES (?, 'default', 'ACTIVE', 'default', 0, 0, 0,
                         'historical-test', ?, ?)
                     """)) {
            profile.setString(1, profileId);
            profile.setString(2, UUID.randomUUID().toString());
            profile.setString(3, owner.toString());
            profile.setLong(4, now);
            profile.setLong(5, now);
            profile.setLong(6, now);
            profile.executeUpdate();
            population.setString(1, profileId);
            population.setLong(2, now);
            population.setLong(3, now);
            population.executeUpdate();
        }
    }

    private static String operationState(
            SqliteConnectionManager connections, String operationId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM companion_population_group_operations WHERE operation_id = ?")) {
            statement.setString(1, operationId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static long scalar(SqliteConnectionManager connections, String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void execute(
            SqliteConnectionManager connections, String sql, String first, String second)
            throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            statement.setString(2, second);
            statement.executeUpdate();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Harness(SqliteConnectionManager connections,
                           PersistenceHealthService health,
                           PersistenceWriteQueue queue,
                           CompanionPopulationRepository population,
                           OwnerPopulationAdmissionCoordinator coordinator,
                           PopulationGroupRepository groups,
                           NpcProfileRepository profiles,
                           PopulationGroupRegistry registry,
                           PopulationGroupOwnerAdmissionExtension extension,
                           List<TameworkEvent> events) implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}
