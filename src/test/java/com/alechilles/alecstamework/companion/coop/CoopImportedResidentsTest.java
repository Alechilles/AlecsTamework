package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopImportedResidentsTest {
    private static final String WORLD = "Default";
    private static final CoopRelease.At AT = new CoopRelease.At(WORLD, 4, 64, 9);
    private static final RestoreFlow.Destination DEST = new RestoreFlow.Destination(WORLD, 4.5, 64, 9.5, 0f, 0f);
    private static final long NOW = -5_000L;

    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
    private final List<RestoreFlow.Request> restores = new ArrayList<>();

    /** A record as the importer writes a 4.x resident: COOP at generation 0, with the old lower-case world key. */
    private UUID imported(int slot, UUID owner) {
        UUID id = UUID.randomUUID();
        index.insert(CompanionRecord.builder(id, "Tamed_Chicken", CompanionLocation.coop("default", 4, 64, 9, slot))
                .ownerUuid(owner).build());
        return id;
    }

    private TameworkCoopSlotsComponent fill(CoopImportedResidents imports, TameworkCoopSlotsComponent current, int max) {
        return imports.fill(WORLD, 4, 64, 9, current, max, NOW).slots();
    }

    @Test
    void importedResidentsJoinTheirOldSlotsOnceAndCanBeReleased() {
        UUID owned = imported(2, UUID.randomUUID());
        UUID unowned = imported(0, null);
        CoopImportedResidents imports = new CoopImportedResidents(index);
        assertTrue(imports.pendingAt(WORLD, 4, 64, 9));

        TameworkCoopSlotsComponent slots = fill(imports, null, 4);

        assertNotNull(slots);
        assertEquals(new TameworkCoopSlotsComponent.Slot(2, owned, 0L, null, NOW), slots.get(2));
        assertEquals(new TameworkCoopSlotsComponent.Slot(0, unowned, 0L, null, NOW), slots.get(0),
                "an imported unowned resident stays a record");
        assertEquals(2, slots.slots().size());
        assertFalse(imports.pendingAt(WORLD, 4, 64, 9));
        assertNull(fill(imports, slots, 4), "a second fill in the same run changes nothing");
        // After a restart the records are still COOP at generation 0 and the block has the entries.
        assertNull(fill(new CoopImportedResidents(index), slots, 4), "a fill after a restart adds nobody twice");

        // The morning release takes the entry as a current resident and restores it at its generation.
        CoopRelease release = new CoopRelease(index::get, new CoopRelease.Port() {
            @Override
            public CompletableFuture<RestoreFlow.Result> restore(RestoreFlow.Request request) {
                restores.add(request);
                return CompletableFuture.completedFuture(RestoreFlow.Result.RESTORED);
            }

            @Override
            public CompletableFuture<Boolean> spawnUnowned(CoopRelease.At at, TameworkCoopSlotsComponent.Slot entry,
                                                           RestoreFlow.Destination destination) {
                throw new AssertionError("an imported resident is a record");
            }

            @Override
            public CompletableFuture<Void> clearSlot(CoopRelease.At at, TameworkCoopSlotsComponent.Slot entry) {
                return CompletableFuture.completedFuture(null);
            }
        }, System::currentTimeMillis);
        assertTrue(release.resident(AT, slots.get(2)));
        assertTrue(release.resident(AT, slots.get(0)));
        assertTrue(release.release(AT, slots.get(2), DEST).join().released());
        assertEquals(owned, restores.get(0).profileId());
        assertEquals(RestoreRules.Reason.COOP_RELEASE, restores.get(0).reason());
        assertEquals(0L, restores.get(0).expectedGeneration());
    }

    @Test
    void aRecordThatLeftTheCoopOrWasTakenInByThisVersionIsNotFilled() {
        UUID recovered = imported(0, UUID.randomUUID());
        UUID retaken = imported(1, UUID.randomUUID());
        CoopImportedResidents imports = new CoopImportedResidents(index);
        // A Recover from the panel before the coop's chunk loaded.
        index.update(recovered, 0L, b -> b.location(CompanionLocation.live(WORLD, 1, 64, 1)).generation(1L));
        // Recovered and taken in again: that intake committed COOP and writes its own slot entry.
        index.update(retaken, 0L, b -> b.generation(2L));

        assertNull(fill(imports, null, 4));
        assertEquals(LocationKind.LIVE, index.get(recovered).location().kind());
    }

    @Test
    void aTakenOrInvalidOldSlotMovesTheResidentToTheNextFreeSlot() {
        UUID first = imported(1, UUID.randomUUID());
        UUID tooHigh = imported(9, UUID.randomUUID());
        TameworkCoopSlotsComponent block = new TameworkCoopSlotsComponent()
                .with(TameworkCoopSlotsComponent.Slot.unowned(1, new BsonDocument()));

        TameworkCoopSlotsComponent slots = fill(new CoopImportedResidents(index), block, 3);

        assertNotNull(slots);
        assertNotNull(slots.get(1).unownedEntity(), "the resident already in slot 1 stays");
        assertEquals(first, slots.get(0).profileId());
        assertEquals(tooHigh, slots.get(2).profileId());
        // The records follow their entries, so each entry counts as occupied.
        assertTrue(CoopSlots.occupied(slots.get(0), index.get(first), WORLD, 4, 64, 9));
        assertTrue(CoopSlots.occupied(slots.get(2), index.get(tooHigh), WORLD, 4, 64, 9));
        assertEquals(0L, index.get(first).generation());
    }

    @Test
    void residentsWaitWhileACoopBlockWithoutAConfigStillStands() {
        UUID waiting = imported(0, UUID.randomUUID());
        CoopImportedResidents imports = new CoopImportedResidents(index);
        CoopImportedResidents.Site site = CoopImportedResidents.Site.of(WORLD, 4, 64, 9);

        assertFalse(imports.seenWithoutCoop(site, 2));
        assertTrue(imports.waitsForCoop(site), "noted once per server run");
        assertFalse(imports.waitsForCoop(site));
        // The block standing started the missing-coop count over.
        assertFalse(imports.seenWithoutCoop(site, 2));

        assertTrue(imports.pendingAt(WORLD, 4, 64, 9));
        assertEquals(LocationKind.COOP, index.get(waiting).location().kind());
        assertEquals(0L, index.get(waiting).generation());
        // Once the config is back the coop is filled as usual.
        assertEquals(waiting, fill(imports, null, 4).get(0).profileId());
    }

    @Test
    void residentsBeyondTheCoopCapacityAreReturnedAsOverflow() {
        UUID kept = imported(0, UUID.randomUUID());
        UUID extra = imported(1, UUID.randomUUID());

        CoopImportedResidents.Fill fill = new CoopImportedResidents(index).fill(WORLD, 4, 64, 9, null, 1, NOW);

        assertEquals(kept, fill.slots().get(0).profileId());
        assertEquals(1, fill.slots().slots().size());
        assertEquals(List.of(extra), fill.overflow().stream().map(CompanionRecord::profileId).toList());
    }

    @Test
    void aResidentWithoutACoopIsReleasedBesideItOrBecomesLostAtItsGeneration() {
        UUID released = imported(0, UUID.randomUUID());
        UUID stuck = imported(1, null);
        CoopImportedResidents imports = new CoopImportedResidents(index);
        CoopImportedResidents.Site site = CoopImportedResidents.Site.of(WORLD, 4, 64, 9);
        assertEquals(List.of(site), imports.sitesIn(WORLD));
        // The chunk must show no coop on several sweeps in a row; an unload starts the count over.
        assertFalse(imports.seenWithoutCoop(site, 2));
        imports.notLoaded(site);
        assertFalse(imports.seenWithoutCoop(site, 2));
        assertTrue(imports.seenWithoutCoop(site, 2));

        List<CompanionRecord> residents = imports.withoutCoop(site);
        assertEquals(List.of(released, stuck), residents.stream().map(CompanionRecord::profileId).toList());
        assertFalse(imports.pendingIn(WORLD));

        assertTrue(imports.moveOut(residents.get(0), DEST, request -> {
            restores.add(request);
            return CompletableFuture.completedFuture(RestoreFlow.Result.RESTORED);
        }).join());
        assertEquals(RestoreRules.Reason.COOP_RELEASE, restores.get(0).reason());
        assertEquals(0L, restores.get(0).expectedGeneration());
        assertEquals(DEST, restores.get(0).destination());

        assertFalse(imports.moveOut(residents.get(1), DEST,
                request -> CompletableFuture.completedFuture(RestoreFlow.Result.SPAWN_FAILED)).join());
        CompanionRecord lost = index.get(stuck);
        assertEquals(LocationKind.LOST, lost.location().kind());
        assertEquals(CoopImportedResidents.CAUSE_COOP_MISSING, lost.location().cause());
        assertEquals(0L, lost.generation(), "the format 0 snapshot written at generation 0 still restores it");
    }
}
