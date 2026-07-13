package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admission coverage for handheld capture of live managed-coop projections. */
class SpawnerManagedCoopCaptureDetachServiceTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final UUID HISTORICAL = uuid(1);
    private static final UUID DEPLOYED = uuid(2);

    @Test
    void exactDeployedProjectionProducesDurableDetachContext() {
        ManagedCoopResidentIndex index = populatedIndex();
        SpawnerManagedCoopCaptureDetachService service = service(index, true);

        SpawnerManagedCoopCaptureDetachService.Plan plan = service.prepare(DEPLOYED);

        assertTrue(plan.accepted());
        assertTrue(plan.requiresDetach());
        assertNull(plan.detail());
        assertTrue(plan.durableContextJson().contains("\"mode\":\"DETACH\""));
        assertTrue(plan.durableContextJson().contains(DEPLOYED.toString()));
        assertTrue(plan.durableContextJson().contains("\"expectedResidentGeneration\":7"));
    }

    @Test
    void historicalAliasAndUntrustedIndexFailClosed() {
        ManagedCoopResidentIndex index = populatedIndex();

        SpawnerManagedCoopCaptureDetachService.Plan historical =
                service(index, true).prepare(HISTORICAL);
        SpawnerManagedCoopCaptureDetachService.Plan untrusted =
                service(index, false).prepare(DEPLOYED);

        assertFalse(historical.accepted());
        assertEquals("managed_coop_capture_source_not_current_deployed_resident",
                historical.detail());
        assertFalse(untrusted.accepted());
        assertEquals("managed_coop_capture_index_unavailable", untrusted.detail());
    }

    @Test
    void ordinaryCompanionNeedsNoManagedContext() {
        ManagedCoopResidentIndex index = populatedIndex();

        SpawnerManagedCoopCaptureDetachService.Plan plan =
                service(index, true).prepare(uuid(99));

        assertTrue(plan.accepted());
        assertFalse(plan.requiresDetach());
        assertNull(plan.durableContextJson());
    }

    private static SpawnerManagedCoopCaptureDetachService service(
            ManagedCoopResidentIndex index,
            boolean trusted) {
        return new SpawnerManagedCoopCaptureDetachService(
                index,
                () -> trusted,
                () -> null,
                () -> 123L
        );
    }

    private static ManagedCoopResidentIndex populatedIndex() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        AuthorityRecord authority = new AuthorityRecord(
                AUTHORITY.authorityId(), AUTHORITY, "coop_chicken",
                AuthorityState.TWORK_MANAGED, true, 1, 1L, 2L, null
        );
        ResidentRecord resident = new ResidentRecord(
                "resident", AUTHORITY, "coop_chicken", 0, "profile", "mob_chicken",
                DEPLOYED, HISTORICAL, DEPLOYED, "{}", "hash", 1,
                ResidentState.DEPLOYED, 7L, true, 1L, 2L, 1L, 2L
        );
        assertTrue(index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority)),
                ManagedCoopReadResult.loaded(List.of(resident))
        ).rebuilt());
        return index;
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }
}
