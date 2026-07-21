package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused persistence coverage for null-NPC profiles and all-or-none group reservations. */
class UnifiedPopulationPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void dormantProfilePersistsCanonicalMetadataWithoutInventingNpcIdentity() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("dormant-profile.sqlite")) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, null, "UNKNOWN_DORMANT", "default", 0L);
            NpcProfileRepository repository = new NpcProfileRepository(
                    harness.connections, harness.queue);
            NpcProfileRepository.DormantProfileMutation mutation =
                    new NpcProfileRepository.DormantProfileMutation(
                            profileId, owner, "miniwyvern", "sky_realm", "Spark",
                            "{\"homePosition\":{\"x\":2,\"y\":80,\"z\":-4}}", 20L);

            NpcProfileRepository.DormantProfileResult applied =
                    await(repository.applyDormantProfileAsync(mutation));
            assertEquals(NpcProfileRepository.DormantProfileStatus.APPLIED, applied.status());
            assertNull(applied.profile().currentNpcUuid());
            assertEquals(owner, applied.profile().ownerUuid());
            assertEquals("miniwyvern", applied.profile().roleId());
            assertEquals("Spark", applied.profile().displayName());

            try (Connection connection = harness.connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT current_npc_uuid, last_world_name, state_json
                         FROM npc_profiles WHERE profile_id = ?
                         """)) {
                statement.setString(1, profileId);
                try (ResultSet row = statement.executeQuery()) {
                    assertTrue(row.next());
                    assertNull(row.getString("current_npc_uuid"));
                    assertEquals("sky_realm", row.getString("last_world_name"));
                    assertTrue(row.getString("state_json").contains("homePosition"));
                }
            }

            assertEquals(NpcProfileRepository.DormantProfileStatus.IDEMPOTENT,
                    await(repository.applyDormantProfileAsync(mutation)).status());
        }
    }

    @Test
    void groupReservationDenialWritesNothingAndCanceledEvidenceReleasesCapacity() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("group-reservation.sqlite")) {
            PopulationGroupRepository repository = new PopulationGroupRepository(
                    harness.connections, harness.queue);
            UUID owner = UUID.randomUUID();
            PopulationGroupRepository.ReservationEvidence evidence =
                    new PopulationGroupRepository.ReservationEvidence(
                            owner, "soul_bond", PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL,
                            null, 1, 0, 1, 0, 7L);

            PopulationGroupRepository.ReservationResult first = await(
                    repository.reserveOperationAsync(operation("cap-one", "profile-one", owner),
                            List.of(evidence)));
            assertEquals(PopulationGroupRepository.Status.PREPARED, first.status());
            assertEquals(new PopulationGroupRepository.Counts(0, 0, 1, 0),
                    repository.count(owner, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null));
            PopulationGroupRepository.ReservationEvidence changedEvidence =
                    new PopulationGroupRepository.ReservationEvidence(
                            owner, "soul_bond", PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL,
                            null, 1, 0, 2, 0, 7L);
            assertEquals(PopulationGroupRepository.Status.CONFLICT,
                    await(repository.reserveOperationAsync(
                            operation("cap-one", "profile-one", owner),
                            List.of(changedEvidence))).status());

            PopulationGroupRepository.ReservationResult denied = await(
                    repository.reserveOperationAsync(operation("cap-two", "profile-two", owner),
                            List.of(evidence)));
            assertEquals(PopulationGroupRepository.Status.DENIED, denied.status());
            assertNull(repository.findOperation("cap-two"));
            assertTrue(repository.loadCountEvidence("cap-two").isEmpty());

            assertEquals(PopulationGroupRepository.Status.CANCELED,
                    await(repository.advanceOperationAsync(
                            "cap-one", PopulationGroupOperationRecord.State.PREPARED,
                            PopulationGroupOperationRecord.State.CANCELED, "caller-canceled", 30L)).status());
            assertEquals(new PopulationGroupRepository.Counts(0, 0, 0, 0),
                    repository.count(owner, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null));
            assertEquals(PopulationGroupRepository.Status.PREPARED,
                    await(repository.reserveOperationAsync(
                            operation("cap-two", "profile-two", owner), List.of(evidence))).status());
        }
    }

    @Test
    void dormantCompositeCommitsOwnerProfileAndGroupsAsOneCanonicalState() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("dormant-composite.sqlite")) {
            CompanionPopulationRepository population = new CompanionPopulationRepository(
                    harness.connections, harness.queue);
            PopulationGroupRepository groups = new PopulationGroupRepository(
                    harness.connections, harness.queue);
            NpcProfileRepository profiles = new NpcProfileRepository(
                    harness.connections, harness.queue);
            UUID owner = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            long nowMs = 100L;
            CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                    profileId, null, null, "default", "default", "UNKNOWN_DORMANT",
                    null, null, null, 0L, "provisioning", nowMs, nowMs);
            PopulationPersistenceTransition.Prepare ownerPrepare =
                    new PopulationPersistenceTransition.Prepare(
                            new CompanionPopulationOperationRecord(
                                    "owner-composite", profileId, "PROVISION_DORMANT",
                                    CompanionPopulationOperationRecord.State.PREPARED, 0L,
                                    "{\"ownerUuid\":null}",
                                    "{\"ownerUuid\":\"" + owner + "\"}",
                                    "{\"operation\":\"provision_dormant\"}",
                                    nowMs, nowMs, 0L, null),
                            baseline);
            PopulationGroupOperationRecord groupOperation =
                    operation("group-composite", profileId, owner);
            PopulationGroupRepository.ReservationEvidence groupEvidence =
                    new PopulationGroupRepository.ReservationEvidence(
                            owner, "soul_bond", PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL,
                            null, 1, 0, 1, 0, 7L);

            UnifiedPopulationCompositeStore.ProvisionedDormantPreparationResult prepared = await(
                    population.unifiedPopulationCompositeStore().prepareProvisionedDormantAsync(
                            ownerPrepare, groups, groupOperation, List.of(groupEvidence)));
            assertTrue(prepared.prepared());
            assertEquals(CompanionPopulationOperationRecord.State.APPLYING,
                    population.loadNonterminalOperations().getFirst().state());
            assertEquals(PopulationGroupOperationRecord.State.APPLYING,
                    groups.findOperation("group-composite").state());

            PopulationPersistenceTransition.Commit ownerCommit =
                    new PopulationPersistenceTransition.Commit(
                            "owner-composite", profileId, 0L, ProfileOwnerMutation.set(owner), null,
                            "default", "PROVISIONED_DORMANT", null, null, null, "provisioning");
            NpcProfileRepository.DormantProfileMutation profileMutation =
                    new NpcProfileRepository.DormantProfileMutation(
                            profileId, owner, "miniwyvern", "default", "Spark",
                            "{\"homePosition\":{\"x\":1,\"y\":2,\"z\":3}}", 110L);
            PopulationGroupClassificationRecord classification =
                    new PopulationGroupClassificationRecord(
                            profileId, "miniwyvern", List.of("soul_bond"), 7L,
                            PopulationGroupClassificationRecord.Status.RESOLVED,
                            "provisioning", nowMs, 110L);

            UnifiedPopulationCompositeStore.ProvisionedDormantCommitResult committed = await(
                    population.unifiedPopulationCompositeStore().commitProvisionedDormantAsync(
                            ownerCommit, profiles, profileMutation, groups, "group-composite",
                            new PopulationGroupRepository.ClassificationMutation(null, classification),
                            110L));
            assertTrue(committed.committed());
            CompanionPopulationStateRecord state = population.loadAllStates().getFirst();
            assertEquals(owner, state.ownerUuid());
            assertEquals("PROVISIONED_DORMANT", state.lifecycleState());
            assertNull(state.currentNpcUuid());
            assertEquals("miniwyvern", profiles.loadProfileById(profileId).roleId());
            assertEquals(List.of("soul_bond"), groups.findClassification(profileId).groupIds());
            assertEquals(PopulationGroupOperationRecord.State.COMMITTED,
                    groups.findOperation("group-composite").state());
            assertTrue(population.loadNonterminalOperations().isEmpty());
        }
    }

    private PopulationGroupOperationRecord operation(String operationId, String profileId, UUID owner) {
        return new PopulationGroupOperationRecord(
                operationId, UUID.nameUUIDFromBytes(
                        operationId.getBytes(StandardCharsets.UTF_8)).toString(), profileId,
                "PROVISION_DORMANT", PopulationGroupOperationRecord.State.PREPARED, 0L, 7L,
                null, owner, null, "miniwyvern", List.of(), List.of("soul_bond"),
                null, "PROVISIONED_DORMANT", null, "default", null, "PREPARING",
                10L, 10L, 0L);
    }

    private HydragonPersistenceTestHarness harness(String filename) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(filename));
    }
}
