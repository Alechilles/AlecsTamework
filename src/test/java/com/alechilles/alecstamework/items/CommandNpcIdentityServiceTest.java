package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure regression tests for canonical profile resolution and replacement suppression.
 */
class CommandNpcIdentityServiceTest {
    private static final LoadedNpcIdentityIndex.Location LOCATION_A =
            new LoadedNpcIdentityIndex.Location("world-a", "store-a");
    private static final LoadedNpcIdentityIndex.Location LOCATION_B =
            new LoadedNpcIdentityIndex.Location("world-b", "store-b");

    @Test
    void historicalAliasResolvesToCurrentUuidWithoutAuthorizingUnmarkedReplacement() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current, historical), true,
                emptyFlags(), null, null);
        CommandNpcIdentityService service = service(found(identity), Set.of());

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(historical, null));

        assertEquals(CommandNpcIdentityService.ResolutionStatus.RESOLVED, resolution.status());
        assertEquals("profile-a", resolution.profileId());
        assertEquals(current, resolution.currentNpcUuid());
        assertEquals(List.of(current, historical), resolution.checkedUuids());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void onlyLostAwaitingRecoveryAuthorizesReplacement() {
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileFlags awaitingLost =
                new NpcIdentityRepository.ProfileFlags(false, false, true, false, null, null);
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true,
                awaitingLost, null, null);

        CommandNpcIdentityService.IdentityResolution resolution =
                service(found(identity), Set.of()).resolve(record(current, "profile-a"));

        assertTrue(resolution.durableState().lostAwaitingRecovery());
        assertTrue(resolution.replacementAllowed());
    }

    @Test
    void incompleteIndexMakesUnknownPresenceFailClosed() {
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileFlags awaitingLost =
                new NpcIdentityRepository.ProfileFlags(false, false, true, false, null, null);
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true,
                awaitingLost, null, null);
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> found(identity),
                index::probe
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(current, "profile-a"));

        assertEquals(CommandNpcIdentityService.ResolutionStatus.FAILED, resolution.status());
        assertEquals("loaded_identity_index_incomplete", resolution.failureReason());
        assertEquals(List.of(current), resolution.checkedUuids());
        assertTrue(resolution.liveUuids().isEmpty());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void positiveOneLocationResolvesWhileBootstrapIsIncomplete() {
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true,
                emptyFlags(), null, null);
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.recordAdded(current, LOCATION_A);
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> found(identity),
                index::probe
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(current, "profile-a"));

        assertFalse(index.isInitializationComplete());
        assertEquals(CommandNpcIdentityService.ResolutionStatus.RESOLVED, resolution.status());
        assertEquals(List.of(current), resolution.liveUuids());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void oneUuidInMultipleLocationsIsAnIdentityConflict() {
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true,
                emptyFlags(), null, null);
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.recordAdded(current, LOCATION_A);
        index.recordAdded(current, LOCATION_B);
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> found(identity),
                index::probe
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(current, "profile-a"));

        assertEquals(CommandNpcIdentityService.ResolutionStatus.CONFLICT, resolution.status());
        assertEquals("multiple_live_locations_for_uuid", resolution.failureReason());
        assertEquals(List.of(current), resolution.liveUuids());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void liveAliasPlusUnknownAliasStillFailsClosed() {
        UUID current = UUID.randomUUID();
        UUID historical = UUID.randomUUID();
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current, historical), true,
                emptyFlags(), null, null);
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.recordAdded(current, LOCATION_A);
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> found(identity),
                index::probe
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(historical, "profile-a"));

        assertEquals(CommandNpcIdentityService.ResolutionStatus.FAILED, resolution.status());
        assertEquals("loaded_identity_index_incomplete", resolution.failureReason());
        assertEquals(List.of(current, historical), resolution.checkedUuids());
        assertEquals(List.of(current), resolution.liveUuids());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void completeIndexAbsenceCanAuthorizeLostReplacement() {
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileFlags awaitingLost =
                new NpcIdentityRepository.ProfileFlags(false, false, true, false, null, null);
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true,
                awaitingLost, null, null);
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.markInitializationComplete();
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> found(identity),
                index::probe
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(current, "profile-a"));

        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(current).status());
        assertEquals(CommandNpcIdentityService.ResolutionStatus.RESOLVED, resolution.status());
        assertTrue(resolution.liveUuids().isEmpty());
        assertTrue(resolution.replacementAllowed());
    }

    @Test
    void recoveredLostSnapshotCannotAuthorizeAnotherReplacement() {
        UUID current = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        NpcIdentityRepository.ProfileFlags recoveredLost =
                new NpcIdentityRepository.ProfileFlags(false, false, true, false, null, replacement);
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true,
                recoveredLost, null, null);

        CommandNpcIdentityService.IdentityResolution resolution =
                service(found(identity), Set.of(replacement)).resolve(record(current, "profile-a"));

        assertEquals(replacement, resolution.durableState().lostReplacementUuid());
        assertTrue(resolution.checkedUuids().contains(replacement));
        assertEquals(List.of(replacement), resolution.liveUuids());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void liveHistoricalAliasSuppressesReplacement() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current, historical), true,
                emptyFlags(), null, null);
        CommandNpcIdentityService service = service(found(identity), Set.of(historical));

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(historical, "profile-a"));

        assertEquals(CommandNpcIdentityService.ResolutionStatus.RESOLVED, resolution.status());
        assertEquals(List.of(historical), resolution.liveUuids());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void twoLiveAliasesAreAnIdentityConflict() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current, historical), true,
                emptyFlags(), null, null);
        CommandNpcIdentityService service = service(found(identity), Set.of(current, historical));

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(historical, "profile-a"));

        assertEquals(CommandNpcIdentityService.ResolutionStatus.CONFLICT, resolution.status());
        assertEquals(2, resolution.liveUuids().size());
        assertEquals("multiple_live_profile_aliases", resolution.failureReason());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void unknownCachedUuidIsAlsoCheckedForLivePresence() {
        UUID unknownCached = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        ArrayList<UUID> probed = new ArrayList<>();
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), false,
                emptyFlags(), null, null);
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> found(identity),
                npcUuid -> {
                    probed.add(npcUuid);
                    return npcUuid.equals(unknownCached)
                            ? oneLocation(npcUuid)
                            : absent(npcUuid);
                }
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(unknownCached, "profile-a"));

        assertEquals(List.of(current, unknownCached), probed);
        assertEquals(List.of(unknownCached), resolution.liveUuids());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void everyDurableSuppressorBlocksReplacement() {
        UUID current = UUID.randomUUID();
        NpcIdentityRepository.ProfileFlags flags =
                new NpcIdentityRepository.ProfileFlags(
                        true, true, true, true, "legacy-slot", UUID.randomUUID());
        NpcIdentityRepository.ManagedAssignment managed = new NpcIdentityRepository.ManagedAssignment(
                "resident-a", "authority-a", 0, current, current, null,
                ManagedCoopResidentRepository.ResidentState.HOUSED, 4L);
        NpcIdentityRepository.ActiveRecovery recovery = new NpcIdentityRepository.ActiveRecovery(
                "recovery-a", NpcRecoveryOperationRepository.RecoveryState.PREPARED,
                UUID.randomUUID(), null, 2L);
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true, flags, managed, recovery);

        CommandNpcIdentityService.IdentityResolution resolution =
                service(found(identity), Set.of()).resolve(record(current, "profile-a"));

        assertTrue(resolution.durableState().captured());
        assertTrue(resolution.durableState().dead());
        assertTrue(resolution.durableState().lost());
        assertTrue(resolution.durableState().legacyCoop());
        assertTrue(resolution.durableState().managedCoop());
        assertTrue(resolution.durableState().activeRecovery());
        assertFalse(resolution.replacementAllowed());
    }

    @Test
    void eachDurableBlockerIndependentlySuppressesLostReplacement() {
        UUID replacement = UUID.randomUUID();
        List<CommandNpcIdentityService.DurableStateFlags> blockers = List.of(
                new CommandNpcIdentityService.DurableStateFlags(
                        true, false, true, null, false, false, false),
                new CommandNpcIdentityService.DurableStateFlags(
                        false, true, true, null, false, false, false),
                new CommandNpcIdentityService.DurableStateFlags(
                        false, false, true, replacement, false, false, false),
                new CommandNpcIdentityService.DurableStateFlags(
                        false, false, true, null, true, false, false),
                new CommandNpcIdentityService.DurableStateFlags(
                        false, false, true, null, false, true, false),
                new CommandNpcIdentityService.DurableStateFlags(
                        false, false, true, null, false, false, true)
        );

        for (CommandNpcIdentityService.DurableStateFlags blocker : blockers) {
            assertTrue(blocker.lostAwaitingRecovery() || blocker.lostReplacementUuid() != null);
            assertTrue(blocker.suppressesReplacement());
        }
    }

    @Test
    void managedAndRecoveryProjectionEvidenceParticipatesInLiveConflictDetection() {
        UUID current = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID deployed = UUID.randomUUID();
        UUID planned = UUID.randomUUID();
        UUID actual = UUID.randomUUID();
        NpcIdentityRepository.ManagedAssignment managed = new NpcIdentityRepository.ManagedAssignment(
                "resident-a", "authority-a", 0, current, source, deployed,
                ManagedCoopResidentRepository.ResidentState.DEPLOYED, 4L);
        NpcIdentityRepository.ActiveRecovery recovery = new NpcIdentityRepository.ActiveRecovery(
                "recovery-a", NpcRecoveryOperationRepository.RecoveryState.SPAWN_CLAIMED,
                planned, actual, 2L);
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current), true,
                emptyFlags(), managed, recovery);

        CommandNpcIdentityService.IdentityResolution resolution = service(
                found(identity),
                Set.of(source, planned)
        ).resolve(record(current, "profile-a"));

        assertTrue(resolution.checkedUuids().containsAll(List.of(
                current, source, deployed, planned, actual
        )));
        assertEquals(Set.of(source, planned), Set.copyOf(resolution.liveUuids()));
        assertEquals(CommandNpcIdentityService.ResolutionStatus.CONFLICT, resolution.status());
    }

    @Test
    void repositoryFailureFailsClosed() {
        SQLException failure = new SQLException("database unavailable");
        NpcIdentityRepository.IdentityLoadResult failed = new NpcIdentityRepository.IdentityLoadResult(
                NpcIdentityRepository.LoadStatus.FAILED, null, null, null,
                "identity_db_failed", failure);
        LinkedNpcRecord original = record(UUID.randomUUID(), "profile-a");
        CommandNpcIdentityService service = service(failed, Set.of());

        CommandNpcIdentityService.IdentityResolution resolution = service.resolve(original);

        assertEquals(CommandNpcIdentityService.ResolutionStatus.FAILED, resolution.status());
        assertSame(failure, resolution.failure());
        assertFalse(resolution.replacementAllowed());
        assertSame(original, service.canonicalRecord(original, resolution));
    }

    @Test
    void productionIdentityPathUsesTypedIndexProbeWithoutUniverseScan() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandNpcIdentityService.java"
        ));

        assertTrue(source.contains("existenceService::probe"));
        assertFalse(source.contains("findLiveNpc"));
        assertFalse(source.contains("Universe"));
    }

    @Test
    void repositoryConflictFailsClosedWithoutChoosingAProfile() {
        NpcIdentityRepository.IdentityLoadResult conflict = new NpcIdentityRepository.IdentityLoadResult(
                NpcIdentityRepository.LoadStatus.CONFLICT, null,
                "profile-a", "profile-b", "profile_and_uuid_resolve_differently", null);
        LinkedNpcRecord original = record(UUID.randomUUID(), "profile-a");
        CommandNpcIdentityService service = service(conflict, Set.of());

        CommandNpcIdentityService.IdentityResolution resolution = service.resolve(original);

        assertEquals(CommandNpcIdentityService.ResolutionStatus.CONFLICT, resolution.status());
        assertEquals("profile-a", resolution.profileId());
        assertEquals("profile-b", resolution.conflictingProfileId());
        assertFalse(resolution.replacementAllowed());
        assertSame(original, service.canonicalRecord(original, resolution));
    }

    @Test
    void unresolvedLegacyRecordIsPreserved() {
        NpcIdentityRepository.IdentityLoadResult missing = new NpcIdentityRepository.IdentityLoadResult(
                NpcIdentityRepository.LoadStatus.NOT_FOUND, null, null, null, null, null);
        LinkedNpcRecord original = record(UUID.randomUUID(), null);
        CommandNpcIdentityService service = service(missing, Set.of());

        CommandNpcIdentityService.IdentityResolution resolution = service.resolve(original);

        assertEquals(CommandNpcIdentityService.ResolutionStatus.UNRESOLVED, resolution.status());
        assertNull(resolution.profileId());
        assertFalse(resolution.replacementAllowed());
        assertSame(original, service.canonicalRecord(original, resolution));
    }

    @Test
    void canonicalRecordReplacesIdentityAndPreservesEveryCachedField() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        LinkedNpcRecord original = detailedRecord(historical, null);
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current, historical), true,
                emptyFlags(), null, null);
        CommandNpcIdentityService service = service(found(identity), Set.of());

        LinkedNpcRecord canonical = service.canonicalRecord(original, service.resolve(original));

        assertEquals(current, canonical.npcUuid);
        assertEquals("profile-a", canonical.profileId);
        assertEquals(original.lastKnownPosition, canonical.lastKnownPosition);
        assertEquals(original.lastKnownWorldName, canonical.lastKnownWorldName);
        assertEquals(original.homePosition, canonical.homePosition);
        assertEquals(original.cachedDisplayName, canonical.cachedDisplayName);
        assertEquals(original.cachedNameKey, canonical.cachedNameKey);
        assertEquals(original.cachedRoleId, canonical.cachedRoleId);
        assertEquals(original.cachedCommandState, canonical.cachedCommandState);
        assertEquals(original.active, canonical.active);
        assertEquals(original.breedingEnabled, canonical.breedingEnabled);
        assertEquals(original.groupId, canonical.groupId);
    }

    @Test
    void canonicalizationDeduplicatesOneProfileWithoutMergingAnother() {
        UUID aliasA = UUID.randomUUID();
        UUID aliasB = UUID.randomUUID();
        UUID sharedCurrent = UUID.randomUUID();
        UUID profileBSource = UUID.randomUUID();
        UUID unresolved = UUID.randomUUID();
        NpcIdentityRepository.ProfileIdentity profileA = identity(
                "profile-a", sharedCurrent, List.of(sharedCurrent, aliasA, aliasB), true,
                emptyFlags(), null, null);
        NpcIdentityRepository.ProfileIdentity profileB = identity(
                "profile-b", sharedCurrent, List.of(sharedCurrent, profileBSource), true,
                emptyFlags(), null, null);
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> {
                    if ("profile-a".equals(profileId)) return found(profileA);
                    if ("profile-b".equals(profileId)) return found(profileB);
                    return new NpcIdentityRepository.IdentityLoadResult(
                            NpcIdentityRepository.LoadStatus.NOT_FOUND,
                            null, null, null, null, null);
                },
                this::absent
        );

        CommandNpcIdentityService.CanonicalizationResult result = service.canonicalize(List.of(
                record(aliasA, "profile-a"),
                record(aliasB, "profile-a"),
                record(profileBSource, "profile-b"),
                record(unresolved, null),
                record(unresolved, null)
        ));

        assertEquals(3, result.records().size());
        assertEquals(1, countProfile(result.records(), "profile-a"));
        assertEquals(1, countProfile(result.records(), "profile-b"));
        assertEquals(1, countProfile(result.records(), null));
        assertFalse(result.hasConflicts());
        assertFalse(result.hasFailures());
    }

    @Test
    void canonicalizationPreservesWholeInputWhenAnyIdentityConflicts() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        LinkedNpcRecord resolvable = detailedRecord(historical, "profile-a");
        LinkedNpcRecord conflicting = detailedRecord(UUID.randomUUID(), "profile-b");
        LinkedNpcRecord duplicateConflict = detailedRecord(UUID.randomUUID(), "profile-b");
        NpcIdentityRepository.ProfileIdentity identity = identity(
                "profile-a", current, List.of(current, historical), true,
                emptyFlags(), null, null);
        CommandNpcIdentityService service = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> "profile-a".equals(profileId)
                        ? found(identity)
                        : new NpcIdentityRepository.IdentityLoadResult(
                                NpcIdentityRepository.LoadStatus.CONFLICT,
                                null,
                                "profile-b",
                                "profile-c",
                                "profile_and_uuid_resolve_differently",
                                null
                        ),
                this::absent
        );

        CommandNpcIdentityService.CanonicalizationResult result =
                service.canonicalize(List.of(resolvable, conflicting, duplicateConflict));

        assertTrue(result.hasConflicts());
        assertEquals(3, result.records().size());
        assertSame(resolvable, result.records().get(0));
        assertSame(conflicting, result.records().get(1));
        assertSame(duplicateConflict, result.records().get(2));
    }

    @Test
    void canonicalizationPreservesDuplicateRecordsWhenIdentityReadFails() {
        LinkedNpcRecord first = detailedRecord(UUID.randomUUID(), "profile-a");
        LinkedNpcRecord second = detailedRecord(UUID.randomUUID(), "profile-a");
        NpcIdentityRepository.IdentityLoadResult failed = new NpcIdentityRepository.IdentityLoadResult(
                NpcIdentityRepository.LoadStatus.FAILED,
                null,
                null,
                null,
                "identity_db_failed",
                new SQLException("database unavailable")
        );

        CommandNpcIdentityService.CanonicalizationResult result =
                service(failed, Set.of()).canonicalize(List.of(first, second));

        assertTrue(result.hasFailures());
        assertEquals(2, result.records().size());
        assertSame(first, result.records().get(0));
        assertSame(second, result.records().get(1));
    }

    private CommandNpcIdentityService service(NpcIdentityRepository.IdentityLoadResult result,
                                              Set<UUID> liveUuids) {
        return new CommandNpcIdentityService(
                (profileId, historicalUuid) -> result,
                npcUuid -> liveUuids.contains(npcUuid)
                        ? oneLocation(npcUuid)
                        : absent(npcUuid)
        );
    }

    private LoadedNpcIdentityIndex.Probe oneLocation(UUID npcUuid) {
        return probe(npcUuid, LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, LOCATION_A);
    }

    private LoadedNpcIdentityIndex.Probe absent(UUID npcUuid) {
        return probe(npcUuid, LoadedNpcIdentityIndex.ProbeStatus.ABSENT);
    }

    private LoadedNpcIdentityIndex.Probe probe(UUID npcUuid,
                                               LoadedNpcIdentityIndex.ProbeStatus status,
                                               LoadedNpcIdentityIndex.Location... locations) {
        return new LoadedNpcIdentityIndex.Probe(npcUuid, status, List.of(locations));
    }

    private NpcIdentityRepository.IdentityLoadResult found(
            NpcIdentityRepository.ProfileIdentity identity) {
        return new NpcIdentityRepository.IdentityLoadResult(
                NpcIdentityRepository.LoadStatus.FOUND, identity,
                null, null, null, null);
    }

    private NpcIdentityRepository.ProfileIdentity identity(
            String profileId,
            UUID currentUuid,
            List<UUID> aliases,
            boolean historicalUuidKnown,
            NpcIdentityRepository.ProfileFlags flags,
            NpcIdentityRepository.ManagedAssignment managed,
            NpcIdentityRepository.ActiveRecovery recovery) {
        return new NpcIdentityRepository.ProfileIdentity(
                profileId, currentUuid, aliases, historicalUuidKnown,
                flags, managed, recovery);
    }

    private NpcIdentityRepository.ProfileFlags emptyFlags() {
        return new NpcIdentityRepository.ProfileFlags(false, false, false, false, null, null);
    }

    private LinkedNpcRecord record(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(
                npcUuid, profileId, null, null, null,
                null, null, null, null, true, false, null);
    }

    private LinkedNpcRecord detailedRecord(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(
                npcUuid, profileId, new Vector3d(1, 2, 3), "world-a", new Vector3d(4, 5, 6),
                "Display", "name.key", "Mob_Test", "Follow", false, true, "group-a");
    }

    private int countProfile(List<LinkedNpcRecord> records, String profileId) {
        int count = 0;
        for (LinkedNpcRecord record : records) {
            if (profileId == null ? record.profileId == null : profileId.equals(record.profileId)) {
                count++;
            }
        }
        return count;
    }
}
