package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.HousedResidentClaim;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for command-query visibility of canonical schema-v5 assignments. */
class CommandLinkedNpcCoopManagedAssignmentTest {
    private static final ManagedCoopAuthorityKey KEY =
            new ManagedCoopAuthorityKey("default", 12, 70, 12);

    @TempDir
    Path tempDir;

    @Test
    void trustedHousedAssignmentFeedsPanelToolOwnerAndSlotQueries() {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            NpcProfileRepository profiles = runtime.getNpcProfileRepository();
            assertTrue(profiles.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid, ownerUuid, null, "tamed_chicken", "Clucky", null, true,
                    "coop_chicken", 1, null, new String[] {"wand-a"})));
            assertTrue(runtime.awaitWriteQueueIdle(5_000L));
            String profileId = profiles.resolveProfileId(npcUuid);
            assertNotNull(profileId);

            ManagedCoopResidentIndex index = managedIndex(resident(
                    profileId, npcUuid, npcUuid, null, ResidentState.HOUSED, 1));
            CommandLinkedNpcCoopService service = facade(runtime, index, () -> true);

            CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot byTool =
                    service.getCoopSnapshotForToolOrOwner(npcUuid, "wand-a", ownerUuid);
            assertNotNull(byTool);
            assertEquals("Clucky", byTool.displayName());
            assertEquals("tamed_chicken", byTool.roleId());
            assertEquals(1, byTool.residentSlot());

            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot slot = service.getLedgerSlotSnapshot(
                    CommandLinkedNpcCoopService.CoopSlotContext.of(
                            "default", "coop_chicken", 12, 70, 12, 1));
            assertNotNull(slot);
            assertEquals(npcUuid, slot.housedNpcUuid());
            assertEquals(ownerUuid, slot.ownerId());
            assertEquals(List.of("wand-a"), List.of(slot.toolIds()));
            assertEquals(1, service.listHousedSlotsForWorld("default").size());
        }
    }

    @Test
    void historicalUuidResolvesCanonicalProfileToHousedAssignment() {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            UUID historicalUuid = UUID.randomUUID();
            UUID intermediateUuid = UUID.randomUUID();
            UUID currentUuid = UUID.randomUUID();
            NpcProfileRepository profiles = runtime.getNpcProfileRepository();
            assertTrue(profiles.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    historicalUuid, null, null, "tamed_chicken", "Alias", null, true,
                    null, null, null, new String[] {"wand-a"})));
            assertTrue(runtime.awaitWriteQueueIdle(5_000L));
            assertTrue(profiles.remapCurrentUuidAsync(historicalUuid, intermediateUuid));
            assertTrue(runtime.awaitWriteQueueIdle(5_000L));
            assertTrue(profiles.remapCurrentUuidAsync(intermediateUuid, currentUuid));
            assertTrue(runtime.awaitWriteQueueIdle(5_000L));
            String profileId = profiles.resolveProfileId(historicalUuid);
            assertNotNull(profileId);

            ManagedCoopResidentIndex index = managedIndex(resident(
                    profileId, currentUuid, intermediateUuid, null, ResidentState.HOUSED, 0));
            CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot resolved =
                    facade(runtime, index, () -> true).getCoopSnapshot(historicalUuid);

            assertNotNull(resolved);
            assertEquals(currentUuid, resolved.npcUuid());
            assertEquals("Alias", resolved.displayName());
        }
    }

    @Test
    void releasingAssignmentRemainsVisibleWhileDeployedAssignmentSuppressesStaleV4Row() {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            UUID npcUuid = UUID.randomUUID();
            NpcProfileRepository profiles = runtime.getNpcProfileRepository();
            assertTrue(profiles.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid, null, null, "tamed_chicken", null, null, true,
                    null, null, null, new String[0])));
            assertTrue(runtime.awaitWriteQueueIdle(5_000L));
            String profileId = profiles.resolveProfileId(npcUuid);

            CommandLinkedNpcCoopService legacyWriter = new CommandLinkedNpcCoopService(
                    runtime.getCoopLedgerRepository(), runtime.getHealthService(), profiles);
            legacyWriter.captureResident(
                    npcUuid, "tamed_chicken",
                    CommandLinkedNpcCoopService.CoopSlotContext.of(
                            "default", "coop_chicken", 12, 70, 12, 0),
                    null, null, null, null);
            assertTrue(runtime.awaitWriteQueueIdle(5_000L));

            ManagedCoopResidentIndex releasing = managedIndex(resident(
                    profileId, npcUuid, npcUuid, UUID.randomUUID(), ResidentState.RELEASING, 0));
            assertNotNull(facade(runtime, releasing, () -> true).getCoopSnapshot(npcUuid));

            ManagedCoopResidentIndex deployed = managedIndex(resident(
                    profileId, npcUuid, npcUuid, npcUuid, ResidentState.DEPLOYED, 0));
            assertNull(facade(runtime, deployed, () -> true).getCoopSnapshot(npcUuid));
        }
    }

    @Test
    void persistedDeployedAssignmentIsRecallableButStillSuppressesReplacement() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            PersistedDeployment fixture = persistDeployedAssignment(runtime);

            var managedServices = runtime.getManagedCoopServices();
            assertTrue(managedServices.residentIndexRefreshService().refresh().refreshed());
            CommandLinkedNpcCoopService coopService = facade(
                    runtime, managedServices.residentIndex(), () -> true);
            assertNull(coopService.getCoopSnapshotForToolOrOwner(fixture.npcUuid(), "wand-a", null));

            CommandNpcIdentityService identityService = new CommandNpcIdentityService(
                    runtime.getNpcIdentityRepository()::load,
                    uuid -> new LoadedNpcIdentityIndex.Probe(
                            uuid, LoadedNpcIdentityIndex.ProbeStatus.ABSENT, List.of())
            );
            CommandNpcIdentityService.IdentityResolution identity =
                    identityService.resolve(fixture.commandRecord());
            assertEquals(CommandNpcIdentityService.ResolutionStatus.RESOLVED, identity.status());
            assertTrue(identity.durableState().managedCoop());
            assertTrue(identity.durableState().managedCoopProjectionRelocatable());
            assertTrue(identity.durableState().suppressesReplacement());
            assertFalse(identity.replacementAllowed());

            CommandNpcProfileActionResolver.ActionTarget target =
                    new CommandNpcProfileActionResolver(identityService)
                            .resolveRelocation(fixture.commandRecord());
            assertTrue(target.isActionable());
            assertEquals(fixture.npcUuid(), target.targetNpcUuid());
        }
    }

    @Test
    void untrustedV5IndexFailsClosedInsteadOfFallingBackOrAcceptingLegacyMutation() {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            UUID npcUuid = UUID.randomUUID();
            CommandLinkedNpcCoopService legacyWriter = new CommandLinkedNpcCoopService(
                    runtime.getCoopLedgerRepository(), runtime.getHealthService(),
                    runtime.getNpcProfileRepository());
            CommandLinkedNpcCoopService.CoopSlotContext slot =
                    CommandLinkedNpcCoopService.CoopSlotContext.of(
                            "default", "coop_chicken", 12, 70, 12, 0);
            legacyWriter.captureResident(npcUuid, "tamed_chicken", slot,
                    null, null, null, null);
            assertTrue(runtime.awaitWriteQueueIdle(5_000L));

            ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
            CommandLinkedNpcCoopService facade = facade(runtime, index, () -> false);
            assertNull(facade.getCoopSnapshot(npcUuid));

            UUID rejected = UUID.randomUUID();
            facade.captureResident(rejected, "tamed_chicken", slot,
                    null, null, null, null);
            assertNull(facade.getCoopSnapshot(rejected));
            assertFalse(facade.resolveRelease(rejected, "tamed_chicken", slot, false)
                    .failureReason().isBlank());
        }
    }

    private CommandLinkedNpcCoopService facade(TameworkPersistenceRuntime runtime,
                                                ManagedCoopResidentIndex index,
                                                java.util.function.BooleanSupplier trust) {
        return new CommandLinkedNpcCoopService(
                runtime.getCoopLedgerRepository(),
                runtime.getHealthService(),
                runtime.getNpcProfileRepository(),
                index,
                trust
        );
    }

    private ManagedCoopResidentIndex managedIndex(ResidentRecord resident) {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ManagedCoopResidentIndex.RebuildResult result = index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority())),
                ManagedCoopReadResult.loaded(List.of(resident))
        );
        assertTrue(result.rebuilt(), result.detail());
        return index;
    }

    private AuthorityRecord authority() {
        return new AuthorityRecord(
                KEY.authorityId(), KEY, "coop_chicken", AuthorityState.TWORK_MANAGED,
                true, 1, 1L, 1L, null);
    }

    private ResidentRecord resident(String profileId,
                                    UUID residentUuid,
                                    UUID sourceUuid,
                                    UUID deployedUuid,
                                    ResidentState state,
                                    int slot) {
        return new ResidentRecord(
                "resident-" + slot, KEY, "coop_chicken", slot, profileId,
                "tamed_chicken", residentUuid, sourceUuid, deployedUuid,
                null, "hash", 1, state, 0L, true, 10L, 0L, 1L, 10L);
    }

    private PersistedDeployment persistDeployedAssignment(TameworkPersistenceRuntime runtime)
            throws Exception {
        UUID npcUuid = UUID.randomUUID();
        NpcProfileRepository profiles = runtime.getNpcProfileRepository();
        assertTrue(profiles.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                npcUuid, null, null, "tamed_chicken", "Coop Recall", null, true,
                null, null, null, new String[] {"wand-a"})));
        assertTrue(runtime.awaitWriteQueueIdle(5_000L));
        String profileId = profiles.resolveProfileId(npcUuid);
        assertNotNull(profileId);

        ManagedCoopResidentRepository residents = runtime.getManagedCoopResidentRepository();
        assertTrue(await(residents.registerAuthority(
                KEY, "coop_chicken", AuthorityState.TWORK_MANAGED, 1L)).succeeded());
        assertTrue(await(residents.claimHoused(new HousedResidentClaim(
                "resident-recall", KEY, "coop_chicken", 0, profileId,
                "tamed_chicken", npcUuid, "{\"version\":1}", "a".repeat(64), 1, 2L
        ))).succeeded());
        ResidentRecord housed = residents.loadById("resident-recall");
        assertNotNull(housed);
        assertTrue(await(residents.beginRelease(
                housed.residentId(), housed.generation(), npcUuid, 3L)).succeeded());
        ResidentRecord releasing = residents.loadById(housed.residentId());
        assertNotNull(releasing);
        assertTrue(await(residents.finishRelease(
                releasing.residentId(), releasing.generation(), npcUuid, 4L)).succeeded());
        ResidentRecord deployed = residents.loadById(housed.residentId());
        assertNotNull(deployed);
        assertEquals(ResidentState.DEPLOYED, deployed.state());
        assertEquals(npcUuid, deployed.residentUuid());
        assertEquals(npcUuid, deployed.deployedNpcUuid());
        return new PersistedDeployment(npcUuid, new LinkedNpcRecord(
                npcUuid, profileId, null, "default", null,
                "Coop Recall", null, "tamed_chicken", null, true, false, null));
    }

    private <T> T await(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome =
                submission.completion().get(5, TimeUnit.SECONDS);
        assertTrue(outcome.isCommitted(), outcome.failureReason());
        return outcome.value();
    }

    private record PersistedDeployment(UUID npcUuid, LinkedNpcRecord commandRecord) {
    }
}
