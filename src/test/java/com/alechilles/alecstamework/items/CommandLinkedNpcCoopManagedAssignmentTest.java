package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
}
