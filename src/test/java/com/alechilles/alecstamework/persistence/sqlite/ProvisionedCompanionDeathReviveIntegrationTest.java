package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionDeathRecordedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionRevivedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransition;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.items.CompanionReviveEligibilityService;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservation;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservationPersistResult;
import com.alechilles.alecstamework.provisioning.CompanionProvisioningCoordinator;
import com.alechilles.alecstamework.provisioning.ProvisioningPopulationBackend;
import com.alechilles.alecstamework.provisioning.SqliteProvisioningOperationJournal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full canonical loop for command-link-independent provisioned death and same-profile revive. */
class ProvisionedCompanionDeathReviveIntegrationTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ORIGINAL_NPC =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID REVIVED_NPC =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String PROFILE = "profile-provisioned-miniwyvern";
    private static final String ROLE = "Tamed_Wyvern_Mini";

    @TempDir
    Path tempDir;

    @Test
    void activeProvisionedDeathRetainsOwnedProfileAndDeniedThenSuccessfulReviveReusesIt()
            throws Exception {
        CompanionReviveEligibilityService previous = CompanionReviveEligibilityService.current();
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("provisioned-death-revive.sqlite"))) {
            insertActiveProfile(harness);
            NpcProfileRepository profiles = new NpcProfileRepository(
                    harness.connections, harness.queue);
            CompanionPopulationObservationRepository observations =
                    new CompanionPopulationObservationRepository(harness.queue);
            CompanionPopulationRepository populations = new CompanionPopulationRepository(
                    harness.connections, harness.queue);
            CompanionProvisioningRepository provisioning = new CompanionProvisioningRepository(
                    harness.connections, harness.queue);

            CompanionPopulationObservation active = new CompanionPopulationObservation(
                    PROFILE, ORIGINAL_NPC, OWNER, "default", CompanionLifecycleState.ACTIVE,
                    "default", 4, -2, 0L, "provisioned-test-active");
            CompanionPopulationObservationPersistResult activeResult =
                    observations.persistAsync(active).get(5, TimeUnit.SECONDS);
            assertEquals(CompanionPopulationObservationPersistResult.Status.CREATED,
                    activeResult.status());
            seedCommittedProvisioning(provisioning);

            OwnerPopulationIndex ownerIndex = new OwnerPopulationIndex();
            ownerIndex.replaceCommittedEntries(List.of(new OwnerPopulationEntry(
                    PROFILE, OWNER, "default", CompanionLifecycleState.ACTIVE, 0L)),
                    OwnerPopulationReadiness.READY);
            CompanionReviveEligibilityService eligibility = new CompanionReviveEligibilityService();
            assertTrue(eligibility.bootstrap(provisioning, profiles, ownerIndex).ready());
            List<TameworkEvent> events = new ArrayList<>();
            eligibility.setEventSink(event -> {
                events.add(event);
                throw new IllegalStateException("listener failure must not alter committed state");
            });
            CompanionReviveEligibilityService.install(eligibility);

            CompanionPopulationObservation death = new CompanionPopulationObservation(
                    PROFILE, ORIGINAL_NPC, OWNER, "default",
                    CompanionLifecycleState.DEAD_REVIVABLE,
                    null, null, null, 0L, "ecs-death-component");
            CompanionPopulationObservationPersistResult deathResult =
                    observations.persistAsync(death).get(5, TimeUnit.SECONDS);
            eligibility.onPopulationCommitted(death, deathResult);

            assertEquals(CompanionPopulationObservationPersistResult.Status.COMMITTED,
                    deathResult.status());
            CompanionPopulationStateRecord dead = state(populations);
            assertEquals(PROFILE, dead.profileId());
            assertEquals(OWNER, dead.ownerUuid());
            assertEquals(CompanionLifecycleState.DEAD_REVIVABLE.name(), dead.lifecycleState());
            assertEquals(1L, dead.revision());
            assertNull(dead.physicalWorldName());
            assertNull(dead.physicalChunkX());
            assertNull(dead.physicalChunkZ());
            assertEquals(0, profiles.loadProfileById(PROFILE).toolIds().length,
                    "the provisioned lifecycle must not depend on a command link");
            assertNull(eligibility.findByProfile(PROFILE).currentNpcUuid());
            assertEquals(1, events.size());
            ProvisionedCompanionDeathRecordedEvent deathEvent =
                    (ProvisionedCompanionDeathRecordedEvent) events.getFirst();
            assertEquals(PROFILE, deathEvent.profileId());
            assertEquals(OWNER, deathEvent.ownerUuid());
            assertEquals(ORIGINAL_NPC, deathEvent.lastNpcUuid());
            assertEquals(0L, deathEvent.oldProfileRevision());
            assertEquals(1L, deathEvent.newProfileRevision());

            PopulationBackedTransitionBackend backend = new PopulationBackedTransitionBackend(
                    harness, profiles, observations, populations);
            CompanionProvisioningCoordinator coordinator = new CompanionProvisioningCoordinator(
                    new SqliteProvisioningOperationJournal(provisioning), backend, () -> 1_000L);
            ProvisionedCompanionTransitionRequest deniedRequest = reviveRequest("revive-denied");

            CompanionProvisioningResult denied = coordinator.transition(deniedRequest)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(CompanionProvisioningResult.Status.DENIED, denied.status());
            assertEquals(PROFILE, state(populations).profileId());
            assertEquals(CompanionLifecycleState.DEAD_REVIVABLE.name(),
                    state(populations).lifecycleState());
            assertEquals(1L, state(populations).revision());
            assertEquals(1, events.size());
            assertFalse(backend.replacementProfileCreated);

            backend.admitRevive = true;
            ProvisionedCompanionTransitionRequest acceptedRequest = reviveRequest("revive-accepted");
            CompanionProvisioningResult revived = coordinator.transition(acceptedRequest)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(CompanionProvisioningResult.Status.TRANSITIONED, revived.status());
            assertEquals(PROFILE, revived.profileId());
            assertEquals(OWNER, revived.ownerUuid());
            assertEquals(PopulationCompanionLifecycle.ACTIVE, revived.lifecycle());
            assertEquals(CompanionProvisioningProjectionStatus.ACTIVE,
                    revived.projectionStatus());
            assertEquals(2L, revived.profileRevision());
            CompanionPopulationStateRecord restored = state(populations);
            assertEquals(PROFILE, restored.profileId());
            assertEquals(OWNER, restored.ownerUuid());
            assertEquals(REVIVED_NPC, restored.currentNpcUuid());
            assertEquals(CompanionLifecycleState.ACTIVE.name(), restored.lifecycleState());
            assertEquals("default", restored.physicalWorldName());
            assertEquals(8, restored.physicalChunkX());
            assertEquals(3, restored.physicalChunkZ());
            assertEquals(2L, restored.revision());
            assertFalse(backend.replacementProfileCreated);
            assertEquals(2, events.size());
            ProvisionedCompanionRevivedEvent revivedEvent =
                    (ProvisionedCompanionRevivedEvent) events.get(1);
            assertEquals(PROFILE, revivedEvent.profileId());
            assertEquals(OWNER, revivedEvent.ownerUuid());
            assertEquals(REVIVED_NPC, revivedEvent.newNpcUuid());
            assertEquals(1L, revivedEvent.oldProfileRevision());
            assertEquals(2L, revivedEvent.newProfileRevision());

            CompanionProvisioningResult duplicate = coordinator.transition(acceptedRequest)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(CompanionProvisioningResult.Status.ALREADY_TRANSITIONED,
                    duplicate.status());
            assertEquals(PROFILE, duplicate.profileId());
            assertEquals(2, events.size(), "duplicate callback must not emit another revive event");
        } finally {
            CompanionReviveEligibilityService.install(previous);
        }
    }

    private static ProvisionedCompanionTransitionRequest reviveRequest(String key) {
        return new ProvisionedCompanionTransitionRequest(
                "hydragon", key, OWNER, PROFILE, 1L,
                ProvisionedCompanionTransition.REVIVE_ACTIVE, "default",
                new PopulationAdmissionLocation("default", 8, 3));
    }

    private static CompanionPopulationStateRecord state(
            CompanionPopulationRepository populations) throws Exception {
        return populations.loadAllStates().stream()
                .filter(row -> PROFILE.equals(row.profileId()))
                .findFirst().orElseThrow();
    }

    private static void insertActiveProfile(HydragonPersistenceTestHarness harness)
            throws Exception {
        try (Connection connection = harness.connections.openConnection();
             PreparedStatement profile = connection.prepareStatement("""
                     INSERT INTO npc_profiles (
                         profile_id, current_npc_uuid, owner_uuid, role_id, last_world_name,
                         created_at_ms, updated_at_ms, last_active_at_ms
                     ) VALUES (?, ?, ?, ?, 'default', 1, 1, 1)
                     """)) {
            profile.setString(1, PROFILE);
            profile.setString(2, ORIGINAL_NPC.toString());
            profile.setString(3, OWNER.toString());
            profile.setString(4, ROLE);
            profile.executeUpdate();
        }
    }

    private static void seedCommittedProvisioning(CompanionProvisioningRepository repository)
            throws Exception {
        String operationId = "provision-miniwyvern";
        CompanionProvisioningOperationRecord created = new CompanionProvisioningOperationRecord(
                operationId, "hydragon", "soul-bond", "encounter",
                OWNER, ROLE, CompanionProvisioningOperationRecord.RequestedDisposition.ACTIVE,
                "default", "{\"world\":\"default\"}", "{\"name\":\"Spark\"}",
                1L, PROFILE, null,
                CompanionProvisioningOperationRecord.State.PREPARING_DORMANT,
                null, null, null, null, "NONE", 1L, 1L, 0L);
        assertEquals(CompanionProvisioningRepository.Status.CREATED,
                await(repository.createAsync(created)).status());
        advance(repository, operationId,
                CompanionProvisioningOperationRecord.State.PREPARING_DORMANT,
                CompanionProvisioningOperationRecord.State.DORMANT_PREPARED,
                null, "population-dormant", null, 2L);
        advance(repository, operationId,
                CompanionProvisioningOperationRecord.State.DORMANT_PREPARED,
                CompanionProvisioningOperationRecord.State.DORMANT_APPLYING,
                null, null, null, 3L);
        advance(repository, operationId,
                CompanionProvisioningOperationRecord.State.DORMANT_APPLYING,
                CompanionProvisioningOperationRecord.State.DORMANT_COMMITTED,
                PROFILE, null, null, 4L);
        advance(repository, operationId,
                CompanionProvisioningOperationRecord.State.DORMANT_COMMITTED,
                CompanionProvisioningOperationRecord.State.ACTIVE_PREPARED,
                PROFILE, null, "population-active", 5L);
        advance(repository, operationId,
                CompanionProvisioningOperationRecord.State.ACTIVE_PREPARED,
                CompanionProvisioningOperationRecord.State.ACTIVE_APPLYING,
                PROFILE, null, null, 6L);
        advance(repository, operationId,
                CompanionProvisioningOperationRecord.State.ACTIVE_APPLYING,
                CompanionProvisioningOperationRecord.State.COMMITTED,
                PROFILE, null, null, 7L);
    }

    private static void advance(
            CompanionProvisioningRepository repository,
            String operationId,
            CompanionProvisioningOperationRecord.State expected,
            CompanionProvisioningOperationRecord.State next,
            String canonicalProfileId,
            String dormantPopulationOperationId,
            String activePopulationOperationId,
            long nowMs) throws Exception {
        CompanionProvisioningRepository.MutationResult result = await(repository.advanceAsync(
                new CompanionProvisioningRepository.AdvanceMutation(
                        operationId, expected, next, canonicalProfileId,
                        dormantPopulationOperationId, activePopulationOperationId,
                        next.name(), null, "READY", nowMs)));
        assertTrue(result.status() == CompanionProvisioningRepository.Status.ADVANCED
                || result.status() == CompanionProvisioningRepository.Status.COMMITTED);
    }

    private static final class PopulationBackedTransitionBackend
            implements ProvisioningPopulationBackend {
        private final HydragonPersistenceTestHarness harness;
        private final NpcProfileRepository profiles;
        private final CompanionPopulationObservationRepository observations;
        private final CompanionPopulationRepository populations;
        private boolean admitRevive;
        private boolean replacementProfileCreated;

        private PopulationBackedTransitionBackend(
                HydragonPersistenceTestHarness harness,
                NpcProfileRepository profiles,
                CompanionPopulationObservationRepository observations,
                CompanionPopulationRepository populations) {
            this.harness = harness;
            this.profiles = profiles;
            this.observations = observations;
            this.populations = populations;
        }

        @Override
        public PolicyResolution resolvePolicy(String roleId, long requestedRevision) {
            return new PolicyResolution(false, false, 0L, "not-used");
        }

        @Override
        public CompletionStage<AdmissionPreparation> prepareDormant(DormantRequest request) {
            return CompletableFuture.completedFuture(unavailablePreparation());
        }

        @Override
        public ClaimResult claimDormant(UUID populationOperationId) {
            return new ClaimResult(false, "not-used", null);
        }

        @Override
        public CompletionStage<DormantCommit> commitDormant(
                UUID populationOperationId, DormantProfileDraft profile) {
            return CompletableFuture.completedFuture(new DormantCommit(
                    DormantCommit.Status.UNAVAILABLE, "not-used", null, null));
        }

        @Override
        public CompletionStage<Void> cancelDormant(UUID populationOperationId, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<AdmissionPreparation> prepareActive(ActiveRequest request) {
            return CompletableFuture.completedFuture(unavailablePreparation());
        }

        @Override
        public ClaimResult claimActive(UUID populationOperationId) {
            return new ClaimResult(false, "not-used", null);
        }

        @Override
        public CompletionStage<ProfileSnapshot> commitActive(UUID populationOperationId) {
            return CompletableFuture.failedFuture(new IllegalStateException("not-used"));
        }

        @Override
        public CompletionStage<Void> cancelActive(UUID populationOperationId, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<TransitionOutcome> transition(TransitionRequest request) {
            try {
                CompanionPopulationStateRecord current = state(populations);
                if (current.lifecycleState().equals(CompanionLifecycleState.ACTIVE.name())) {
                    return completed(TransitionOutcome.Status.IDEMPOTENT,
                            "provisioned-companion-already-active", snapshot(current));
                }
                if (!admitRevive) {
                    return completed(TransitionOutcome.Status.DENIED,
                            "population-group-active-cap-reached", null);
                }
                if (!PROFILE.equals(request.profileId())
                        || request.transition() != ProvisionedCompanionTransition.REVIVE_ACTIVE
                        || current.revision() != request.expectedProfileRevision()
                        || !current.lifecycleState().equals(
                        CompanionLifecycleState.DEAD_REVIVABLE.name())) {
                    return completed(TransitionOutcome.Status.DENIED,
                            "provisioned-companion-lifecycle-mismatch", null);
                }
                replacementProfileCreated = !PROFILE.equals(current.profileId());
                assertTrue(profiles.remapCurrentUuidAsync(ORIGINAL_NPC, REVIVED_NPC));
                assertTrue(harness.queue.awaitIdle(5_000L));
                CompanionPopulationObservation restored = new CompanionPopulationObservation(
                        PROFILE, REVIVED_NPC, OWNER, "default", CompanionLifecycleState.ACTIVE,
                        "default", 8, 3, current.revision(),
                        "provisioned-revive-active-test");
                CompanionPopulationObservationPersistResult persisted = observations
                        .persistAsync(restored).get(5, TimeUnit.SECONDS);
                if (!persisted.persisted()) {
                    return completed(TransitionOutcome.Status.QUARANTINED,
                            "provisioned-revive-persistence-failed", null);
                }
                return completed(TransitionOutcome.Status.COMMITTED,
                        "provisioned-companion-transition-committed",
                        snapshot(state(populations)));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public Optional<ProfileSnapshot> findProfile(String profileId) {
            try {
                CompanionPopulationStateRecord current = state(populations);
                return PROFILE.equals(profileId) ? Optional.of(snapshot(current)) : Optional.empty();
            } catch (Exception failure) {
                return Optional.empty();
            }
        }

        private static AdmissionPreparation unavailablePreparation() {
            return new AdmissionPreparation(
                    AdmissionPreparation.Status.UNAVAILABLE, "not-used", null, null);
        }

        private static CompletionStage<TransitionOutcome> completed(
                TransitionOutcome.Status status, String reason, ProfileSnapshot profile) {
            return CompletableFuture.completedFuture(
                    new TransitionOutcome(status, reason, profile, null));
        }

        private static ProfileSnapshot snapshot(CompanionPopulationStateRecord state) {
            PopulationCompanionLifecycle lifecycle = PopulationCompanionLifecycle.valueOf(
                    state.lifecycleState());
            return new ProfileSnapshot(
                    state.profileId(), OWNER, ROLE, lifecycle,
                    lifecycle == PopulationCompanionLifecycle.ACTIVE
                            ? CompanionProvisioningProjectionStatus.ACTIVE
                            : CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE,
                    state.currentNpcUuid(), state.revision(), state.updatedAtMs());
        }
    }
}
