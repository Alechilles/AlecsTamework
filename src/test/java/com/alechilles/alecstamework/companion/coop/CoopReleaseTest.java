package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopReleaseTest {
    private static final CoopRelease.At AT = new CoopRelease.At("default", 4, 64, 9);
    private static final RestoreFlow.Destination DEST = new RestoreFlow.Destination("default", 5.5, 64, 10.5, 0f, 0f);

    private final List<String> events = new ArrayList<>();
    private final List<RestoreFlow.Request> requests = new ArrayList<>();
    private CompletableFuture<Boolean> spawn = new CompletableFuture<>();
    private RestoreFlow.Result restoreResult = RestoreFlow.Result.RESTORED;

    private final CoopRelease release = new CoopRelease(id -> null, new CoopRelease.Port() {
        @Override
        public CompletableFuture<RestoreFlow.Result> restore(RestoreFlow.Request request) {
            events.add("restore");
            requests.add(request);
            return CompletableFuture.completedFuture(restoreResult);
        }

        @Override
        public CompletableFuture<Boolean> spawnUnowned(BsonDocument entity, RestoreFlow.Destination destination) {
            events.add("spawn");
            return spawn;
        }

        @Override
        public CompletableFuture<Void> clearSlot(CoopRelease.At at, TameworkCoopSlotsComponent.Slot entry) {
            events.add("clear " + entry.slot());
            return CompletableFuture.completedFuture(null);
        }
    }, System::currentTimeMillis);

    @Test
    void unownedResidentIsSpawnedOnceAndItsSlotClearedOnlyAfterTheSpawn() {
        TameworkCoopSlotsComponent.Slot entry = TameworkCoopSlotsComponent.Slot.unowned(2,
                new BsonDocument("Components", new BsonString("chicken")));

        CompletableFuture<Boolean> first = release.release(AT, entry, DEST);
        // A second sweep while the spawn is pending must not spawn the resident again.
        assertFalse(release.release(AT, entry, DEST).join());
        assertEquals(List.of("spawn"), events);

        spawn.complete(true);
        assertTrue(first.join());
        assertEquals(List.of("spawn", "clear 2"), events);
    }

    @Test
    void failedUnownedSpawnKeepsTheEntry() {
        TameworkCoopSlotsComponent.Slot entry = TameworkCoopSlotsComponent.Slot.unowned(0,
                new BsonDocument("Components", new BsonString("chicken")));
        spawn.complete(false);

        assertFalse(release.release(AT, entry, DEST).join());
        assertEquals(List.of("spawn"), events);
    }

    @Test
    void companionIsRestoredAtItsGenerationAndItsSlotClearedOnlyWhenRestored() {
        UUID profileId = UUID.randomUUID();
        TameworkCoopSlotsComponent.Slot entry = TameworkCoopSlotsComponent.Slot.companion(1, profileId, 7L);

        restoreResult = RestoreFlow.Result.STALE;
        assertFalse(release.release(AT, entry, DEST).join());
        assertEquals(List.of("restore"), events);

        restoreResult = RestoreFlow.Result.RESTORED;
        assertTrue(release.release(AT, entry, DEST).join());
        assertEquals(List.of("restore", "restore", "clear 1"), events);
        RestoreFlow.Request request = requests.get(1);
        assertEquals(profileId, request.profileId());
        assertEquals(RestoreRules.Reason.COOP_RELEASE, request.reason());
        assertEquals(7L, request.expectedGeneration());
        assertEquals(DEST, request.destination());
    }
}
