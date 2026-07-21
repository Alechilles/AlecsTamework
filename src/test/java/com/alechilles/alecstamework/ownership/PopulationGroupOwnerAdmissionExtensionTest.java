package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupCountEvidenceRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
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

            assertEquals(List.of("hydragon:soulbound_mini"), harness.groups()
                    .findClassification(first.preparedAdmission().plan().transition().profileId())
                    .groupIds());
            PopulationGroupRepository.Counts counts = harness.groups().count(
                    owner, "hydragon:soulbound_mini",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null);
            assertEquals(1, counts.committedOwned());
            assertEquals(1, counts.committedActive());
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

            OwnerPopulationPreparationResult retry = harness.coordinator().prepareAsync(
                    plan(UUID.randomUUID().toString(), owner, "Tamed_Wyvern_Mini"))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(retry.allowed());
            assertTrue(harness.coordinator().cancelAsync(
                    retry.preparedAdmission(), "test-cleanup").get(2, TimeUnit.SECONDS));
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

    private Harness harness() throws Exception {
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
        assertTrue(registry.replace(List.of(group()), 1L).applied());
        PopulationGroupOwnerAdmissionExtension extension =
                new PopulationGroupOwnerAdmissionExtension(coordinator, registry, groups, profiles);
        assertTrue(extension.recover().get(2, TimeUnit.SECONDS).ready());
        coordinator.installPopulationGroups(extension);
        return new Harness(queue, coordinator, groups);
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

    private static TwPopulationGroupConfig group() throws Exception {
        var constructor = TwPopulationGroupConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwPopulationGroupConfig config = constructor.newInstance();
        set(config, "id", "HyDragon_Soulbound_Mini");
        set(config, "groupId", "hydragon:soulbound_mini");
        set(config, "priority", 100);
        set(config, "roleIds", new String[] {"Tamed_Wyvern_Mini"});
        Object limits = field(config, "limits");
        set(limits, "maxOwnedPerOwner", 1);
        set(limits, "maxActivePerOwner", 1);
        set(limits, "scope", PopulationGroupScope.GLOBAL);
        return config;
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

    private record Harness(PersistenceWriteQueue queue,
                           OwnerPopulationAdmissionCoordinator coordinator,
                           PopulationGroupRepository groups) implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}
