package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies fail-closed managed occupancy and first-slot selection. */
class ManagedCoopOccupancyServiceTest {
    private static final ManagedCoopAuthorityKey KEY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void unreadIndexBlocksCaptureAndRelease() throws Exception {
        ManagedCoopOccupancyService service =
                new ManagedCoopOccupancyService(new ManagedCoopResidentIndex());

        ManagedCoopOccupancyService.View view = service.inspect(context("coop_chicken", 3));

        assertEquals(ManagedCoopOccupancyService.AuthorityStatus.INDEX_UNAVAILABLE, view.status());
        assertEquals(-1, service.firstEmptySlot(context("coop_chicken", 3)));
        assertEquals(-1, service.firstHousedSlot(context("coop_chicken", 3)));
    }

    @Test
    void newConfiguredCoopMayClaimFirstSlotAfterSuccessfulEmptyRefresh() throws Exception {
        ManagedCoopResidentIndex index = index(List.of(), List.of());
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index);
        ManagedCoopContext context = context("coop_chicken", 3);

        ManagedCoopOccupancyService.View view = service.inspect(context);

        assertEquals(ManagedCoopOccupancyService.AuthorityStatus.UNREGISTERED, view.status());
        assertTrue(view.permitsCaptureClaim());
        assertEquals(0, service.firstEmptySlot(context));
    }

    @Test
    void committedRowsOwnCapacityAndOnlyHousedRowsRelease() throws Exception {
        ManagedCoopContext context = context("coop_chicken", 3);
        ResidentRecord deployed = resident(0, "profile-a", uuid(1), ResidentState.DEPLOYED);
        ResidentRecord housed = resident(2, "profile-b", uuid(2), ResidentState.HOUSED);
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index(
                List.of(authority("coop_chicken", AuthorityState.TWORK_MANAGED)),
                List.of(deployed, housed)
        ));

        assertEquals(1, service.firstEmptySlot(context));
        assertEquals(2, service.firstHousedSlot(context));
        assertEquals(housed, service.residentAt(context, 2));
        assertEquals(2, service.housedResidentsForWorld(" WORLD ").getFirst().residentSlot());
        assertEquals(deployed, service.residentByUuid(uuid(1)));
    }

    @Test
    void canonicalLifecycleFilterSkipsStaleHousedRowsWithoutStarvingLaterSlots()
            throws Exception {
        ManagedCoopContext context = context("coop_chicken", 3);
        ResidentRecord stale = resident(0, "profile-stale", uuid(1), ResidentState.HOUSED);
        ResidentRecord eligible = resident(2, "profile-cooped", uuid(2), ResidentState.HOUSED);
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index(
                List.of(authority("coop_chicken", AuthorityState.TWORK_MANAGED)),
                List.of(stale, eligible)
        ));

        ResidentRecord selected = service.firstHousedResident(
                context, resident -> resident.profileId().equals("profile-cooped"));

        assertEquals(eligible, selected);
    }

    @Test
    void exactDeployedUuidRecapturesItsReservedSlotAndGeneration() throws Exception {
        ManagedCoopContext context = context("coop_chicken", 1);
        ResidentRecord deployed = resident(
                0, "profile-a", uuid(2), uuid(1), ResidentState.DEPLOYED, 8L);
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index(
                List.of(authority("coop_chicken", AuthorityState.TWORK_MANAGED)),
                List.of(deployed)
        ));

        ManagedCoopOccupancyService.CapturePlacement placement =
                service.resolveCapturePlacement(context, uuid(2), "profile-a");

        assertEquals(ManagedCoopOccupancyService.CapturePlacementStatus.RECAPTURE, placement.status());
        assertEquals(0, placement.residentSlot());
        assertEquals(8L, placement.expectedResidentGeneration());
    }

    @Test
    void overflowResidentsBlockNewIntakeButStillReleaseAndRecapture() throws Exception {
        ManagedCoopContext context = context("coop_chicken", 2);
        ResidentRecord configured = resident(
                0, "profile-a", uuid(1), ResidentState.DEPLOYED);
        ResidentRecord overflowDeployed = resident(
                3, "profile-b", uuid(3), uuid(2), ResidentState.DEPLOYED, 5L);
        ResidentRecord overflowHoused = resident(
                4, "profile-c", uuid(4), ResidentState.HOUSED);
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index(
                List.of(authority("coop_chicken", AuthorityState.TWORK_MANAGED)),
                List.of(configured, overflowDeployed, overflowHoused)
        ));

        assertEquals(-1, service.firstEmptySlot(context),
                "a low-slot hole cannot admit a new resident while total occupancy is over cap");
        assertEquals(4, service.firstHousedSlot(context),
                "overflow residents retain normal scheduled release semantics");

        ManagedCoopOccupancyService.CapturePlacement recapture =
                service.resolveCapturePlacement(context, uuid(3), "profile-b");
        ManagedCoopOccupancyService.CapturePlacement intake =
                service.resolveCapturePlacement(context, uuid(9), null);

        assertEquals(ManagedCoopOccupancyService.CapturePlacementStatus.RECAPTURE,
                recapture.status());
        assertEquals(3, recapture.residentSlot());
        assertEquals(5L, recapture.expectedResidentGeneration());
        assertEquals(ManagedCoopOccupancyService.CapturePlacementStatus.REJECTED,
                intake.status());
        assertEquals("managed_coop_capture_capacity_unavailable", intake.detail());
    }

    @Test
    void historicalAliasAndProfileUuidMismatchCannotRecapture() throws Exception {
        ManagedCoopContext context = context("coop_chicken", 2);
        ResidentRecord deployed = resident(
                0, "profile-a", uuid(2), uuid(1), ResidentState.DEPLOYED, 8L);
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index(
                List.of(authority("coop_chicken", AuthorityState.TWORK_MANAGED)),
                List.of(deployed)
        ));

        ManagedCoopOccupancyService.CapturePlacement historical =
                service.resolveCapturePlacement(context, uuid(1), "profile-a");
        ManagedCoopOccupancyService.CapturePlacement wrongUuid =
                service.resolveCapturePlacement(context, uuid(3), "profile-a");

        assertEquals(ManagedCoopOccupancyService.CapturePlacementStatus.REJECTED, historical.status());
        assertEquals("managed_coop_capture_source_not_current_deployed_resident", historical.detail());
        assertEquals(ManagedCoopOccupancyService.CapturePlacementStatus.REJECTED, wrongUuid.status());
        assertEquals("managed_coop_capture_profile_already_managed_by_other_uuid", wrongUuid.detail());
    }

    @Test
    void persistedAuthorityMismatchOrTransitionBlocksMutation() throws Exception {
        ManagedCoopContext context = context("coop_chicken", 3);
        ManagedCoopOccupancyService conflict = new ManagedCoopOccupancyService(index(
                List.of(authority("coop_duck", AuthorityState.TWORK_MANAGED)),
                List.of()
        ));
        ManagedCoopOccupancyService importing = new ManagedCoopOccupancyService(index(
                List.of(authority("coop_chicken", AuthorityState.IMPORTING_TO_TWORK)),
                List.of(resident(0, "profile-a", uuid(1), ResidentState.HOUSED))
        ));

        assertEquals(ManagedCoopOccupancyService.AuthorityStatus.COOP_ID_CONFLICT,
                conflict.inspect(context).status());
        assertEquals(ManagedCoopOccupancyService.AuthorityStatus.TRANSITION_BLOCKED,
                importing.inspect(context).status());
        assertEquals(-1, conflict.firstEmptySlot(context));
        assertNull(importing.residentAt(context, 0));
        assertTrue(importing.housedResidentsForWorld("world").isEmpty());
    }

    @Test
    void rejectedRefreshRetainsEvidenceButBlocksCapacityDecisions() throws Exception {
        ManagedCoopContext context = context("coop_chicken", 3);
        ManagedCoopResidentIndex index = index(
                List.of(authority("coop_chicken", AuthorityState.TWORK_MANAGED)),
                List.of(resident(0, "profile-a", uuid(1), ResidentState.HOUSED))
        );
        ManagedCoopResidentIndex.Snapshot lastKnownGood = index.snapshot();
        index.rebuild(
                ManagedCoopReadResult.integrityFailure(new IllegalStateException("corrupt")),
                ManagedCoopReadResult.loaded(List.of())
        );
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index);

        assertEquals(lastKnownGood.revision(), index.snapshot().revision());
        assertEquals(ManagedCoopOccupancyService.AuthorityStatus.INDEX_UNAVAILABLE,
                service.inspect(context).status());
        assertEquals(-1, service.firstEmptySlot(context));
        assertTrue(service.housedResidentsForWorld("world").isEmpty());
    }

    @Test
    void compositeTrustGateBlocksAResidentOnlyRefreshEpoch() throws Exception {
        ManagedCoopContext context = context("coop_chicken", 3);
        ManagedCoopResidentIndex index = index(
                List.of(authority("coop_chicken", AuthorityState.TWORK_MANAGED)),
                List.of(resident(0, "profile-a", uuid(1), ResidentState.HOUSED))
        );
        ManagedCoopOccupancyService service = new ManagedCoopOccupancyService(index, () -> false);

        assertEquals(ManagedCoopOccupancyService.AuthorityStatus.INDEX_UNAVAILABLE,
                service.inspect(context).status());
        assertEquals(-1, service.firstHousedSlot(context));
        assertTrue(service.housedResidentsForWorld("world").isEmpty());
        assertNull(service.residentByUuid(uuid(1)));
    }

    private static ManagedCoopResidentIndex index(List<AuthorityRecord> authorities,
                                                  List<ResidentRecord> residents) {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        index.rebuild(ManagedCoopReadResult.loaded(authorities), ManagedCoopReadResult.loaded(residents));
        return index;
    }

    private static AuthorityRecord authority(String coopId, AuthorityState state) {
        return new AuthorityRecord(
                KEY.authorityId(), KEY, coopId, state, true, 1,
                -100L, -90L, null
        );
    }

    private static ResidentRecord resident(int slot,
                                           String profileId,
                                           UUID npcUuid,
                                           ResidentState state) {
        return resident(slot, profileId, npcUuid, npcUuid, state, 0L);
    }

    private static ResidentRecord resident(int slot,
                                           String profileId,
                                           UUID residentUuid,
                                           UUID sourceUuid,
                                           ResidentState state,
                                           long generation) {
        return new ResidentRecord(
                "resident-" + profileId, KEY, "coop_chicken", slot, profileId, "Mob_Chicken",
                residentUuid, sourceUuid, state == ResidentState.DEPLOYED ? residentUuid : null,
                "{}", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1,
                state, generation, true, -100L, 0L, -100L, -90L
        );
    }

    private static ManagedCoopContext context(String coopId, int maxResidents) throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", "Coop_Config");
        set(config, "enabled", true);
        set(config, "coopId", coopId);
        set(config.getLifecycleRules(), "maxResidents", maxResidents);
        return new ManagedCoopContext(KEY, coopId, 0, config, null);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }
}
