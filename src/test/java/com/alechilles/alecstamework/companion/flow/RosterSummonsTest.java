package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RosterSummonsTest {
    private static final long NOW = 10_000L;
    private static final RestoreFlow.Destination DEST = new RestoreFlow.Destination("default", 1, 2, 3, 0f, 0f);

    private final UUID owner = UUID.randomUUID();
    private final Map<UUID, CompanionRecord> records = new HashMap<>();
    private final List<RestoreFlow.Request> restores = new ArrayList<>();
    private final List<String> stores = new ArrayList<>();
    private final Map<String, RosterSummons.Policy> policies = Map.of(
            "Timed_Wolf", new RosterSummons.Policy(60_000L, 5_000L, true),
            "Timed_Stays", new RosterSummons.Policy(60_000L, 5_000L, false),
            "Untimed_Sheep", RosterSummons.Policy.UNTIMED);

    private RosterSummons summons() {
        return new RosterSummons(records::get,
                who -> records.values().stream().filter(r -> who.equals(r.ownerUuid())).toList(),
                request -> {
                    restores.add(request);
                    return CompletableFuture.completedFuture(RestoreFlow.Result.RESTORED);
                },
                (id, reason, cooldownUntilMs) -> {
                    stores.add(id + ":" + reason + ":" + cooldownUntilMs);
                    return CompletableFuture.completedFuture(StoreFlow.Result.STORED);
                },
                policies::get, () -> NOW);
    }

    private UUID add(String roleId, CompanionLocation location, long summonedUntilMs) {
        UUID id = UUID.randomUUID();
        records.put(id, CompanionRecord.builder(id, roleId, location)
                .ownerUuid(owner).rosterId("pack").summonedUntilMs(summonedUntilMs).build());
        return id;
    }

    @Test
    void ownerLogoutStoresOnlyTimedLiveSummonsWhoseRoleAllowsItAndDeathStoresEveryTimedSummon() {
        UUID timed = add("Timed_Wolf", CompanionLocation.live("default", 0, 0, 0), NOW + 1_000L);
        UUID staysOnLogout = add("Timed_Stays", CompanionLocation.live("default", 0, 0, 0), NOW + 1_000L);
        add("Untimed_Sheep", CompanionLocation.live("default", 0, 0, 0), 0L);
        add("Timed_Wolf", CompanionLocation.stored(StoredReason.ROSTER), 0L);

        assertEquals(1, summons().storeTimedSummons(owner, true));
        assertEquals(List.of(timed + ":TIMED:15000"), stores);

        stores.clear();
        assertEquals(2, summons().storeTimedSummons(owner, false));
        assertEquals(List.of(staysOnLogout + ":TIMED:15000", timed + ":TIMED:15000").stream().sorted().toList(),
                stores.stream().sorted().toList());
    }

    @Test
    void summonSetsTheTimerFromTheRoleAndAnUntimedRoleGetsNone() {
        UUID timed = add("Timed_Wolf", CompanionLocation.stored(StoredReason.TIMED), 0L);
        UUID untimed = add("Untimed_Sheep", CompanionLocation.stored(StoredReason.ROSTER), 0L);

        summons().summon(timed, DEST).join();
        summons().summon(untimed, DEST).join();

        assertEquals(RestoreRules.Reason.SUMMON, restores.get(0).reason());
        assertEquals(NOW + 60_000L, restores.get(0).summonedUntilMs());
        assertEquals(RestoreRules.Reason.SUMMON, restores.get(1).reason());
        assertEquals(0L, restores.get(1).summonedUntilMs());
    }
}
