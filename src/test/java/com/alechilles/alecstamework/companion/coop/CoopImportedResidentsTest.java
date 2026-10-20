package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
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
        UUID stuck = imported(1, UUID.randomUUID());
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

    private static final String IMPORTED_STATE = "{\"version\":\"1\",\"npcUuid\":\"" + UUID.randomUUID()
            + "\",\"roleId\":\"Tamed_Chicken\",\"healthPercent\":100.0}";

    /**
     * The production restore flow over this index. Every record has the format 0 snapshot the
     * importer wrote; the spawner records the committed record it is handed and answers {@code spawns}.
     */
    private RestoreFlow<String> restoreFlow(List<CompanionRecord> handed, boolean spawns) {
        return new RestoreFlow<>(index, new LoadedBodies<>(),
                id -> CompletableFuture.completedFuture(SnapshotEnvelope.importedState(id, 0L, IMPORTED_STATE)),
                owner -> CompletableFuture.completedFuture(null),
                (committed, snapshot, destination, reason) -> {
                    assertEquals(SnapshotEnvelope.FORMAT_IMPORTED_STATE, snapshot.format());
                    handed.add(committed);
                    return CompletableFuture.completedFuture(spawns);
                },
                (id, body) -> { }, System::currentTimeMillis, (before, after) -> null);
    }

    private CoopRelease morningRelease(RestoreFlow<String> flow, List<Integer> cleared) {
        return new CoopRelease(index::get, new CoopRelease.Port() {
            @Override
            public CompletableFuture<RestoreFlow.Result> restore(RestoreFlow.Request request) {
                return flow.restore(request);
            }

            @Override
            public CompletableFuture<Boolean> spawnUnowned(CoopRelease.At at, TameworkCoopSlotsComponent.Slot entry,
                                                           RestoreFlow.Destination destination) {
                throw new AssertionError("an imported resident is a record");
            }

            @Override
            public CompletableFuture<Void> clearSlot(CoopRelease.At at, TameworkCoopSlotsComponent.Slot entry) {
                cleared.add(entry.slot());
                return CompletableFuture.completedFuture(null);
            }
        }, System::currentTimeMillis);
    }

    /**
     * An imported resident with no owner used to commit LIVE with no owner, which the spawn from
     * the role refuses, so it never left its coop. It must leave as an unowned release: the
     * spawner gets a tombstone (it then builds an untracked body) and the slot is cleared.
     */
    @Test
    void anUnownedImportedResidentLeavesItsCoopAsAnUntrackedAnimal() {
        UUID unowned = imported(0, null);
        TameworkCoopSlotsComponent slots = fill(new CoopImportedResidents(index), null, 4);
        List<CompanionRecord> handed = new ArrayList<>();
        List<Integer> cleared = new ArrayList<>();

        CoopRelease.Outcome outcome = morningRelease(restoreFlow(handed, true), cleared)
                .release(AT, slots.get(0), DEST).join();

        assertTrue(outcome.released(), outcome.cause());
        assertEquals(LocationKind.RELEASED, handed.get(0).location().kind());
        assertNull(handed.get(0).ownerUuid());
        assertEquals(LocationKind.RELEASED, index.get(unowned).location().kind());
        assertEquals(List.of(0), cleared);
    }

    @Test
    void anUnownedImportedResidentWhoseSpawnFailsStaysInItsSlotForTheNextTry() {
        UUID unowned = imported(0, null);
        TameworkCoopSlotsComponent slots = fill(new CoopImportedResidents(index), null, 4);
        List<Integer> cleared = new ArrayList<>();
        CoopRelease release = morningRelease(restoreFlow(new ArrayList<>(), false), cleared);

        assertEquals("SPAWN_FAILED", release.release(AT, slots.get(0), DEST).join().cause());

        assertEquals(LocationKind.COOP, index.get(unowned).location().kind());
        assertEquals(0L, index.get(unowned).generation());
        assertTrue(cleared.isEmpty());
        assertTrue(CoopSlots.occupied(slots.get(0), index.get(unowned), WORLD, 4, 64, 9));
    }

    @Test
    void anOwnedImportedResidentStillComesOutOwnedAndTracked() {
        UUID owner = UUID.randomUUID();
        UUID owned = imported(0, owner);
        TameworkCoopSlotsComponent slots = fill(new CoopImportedResidents(index), null, 4);

        assertTrue(morningRelease(restoreFlow(new ArrayList<>(), true), new ArrayList<>())
                .release(AT, slots.get(0), DEST).join().released());

        CompanionRecord after = index.get(owned);
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(owner, after.ownerUuid());
        assertEquals(1L, after.generation());
    }

    /** The coop is full (overflow) or gone: both hand the resident to {@code moveOut}. */
    @Test
    void anUnownedImportedResidentWithoutASlotIsReleasedOrWaitsForTheNextServerStart() {
        imported(0, UUID.randomUUID());
        UUID overflow = imported(1, null);
        UUID gone = UUID.randomUUID();
        index.insert(CompanionRecord.builder(gone, "Tamed_Chicken", CompanionLocation.coop("default", 40, 64, 9, 0))
                .build());
        CoopImportedResidents imports = new CoopImportedResidents(index);
        CoopImportedResidents.Site goneSite = CoopImportedResidents.Site.of(WORLD, 40, 64, 9);
        List<CompanionRecord> full = imports.fill(WORLD, 4, 64, 9, null, 1, NOW).overflow();
        assertEquals(List.of(overflow), full.stream().map(CompanionRecord::profileId).toList());

        // The spawn works: the animal is out, untracked, and its record is a tombstone.
        assertTrue(imports.moveOut(full.get(0), DEST, restoreFlow(new ArrayList<>(), true)::restore).join());
        assertEquals(LocationKind.RELEASED, index.get(overflow).location().kind());

        // The spawn fails (its role is not loaded, say): LOST would leave a record nobody owns and
        // nobody can recover, so it stays an imported resident and the next server start tries again.
        CompanionRecord waiting = imports.withoutCoop(goneSite).get(0);
        assertFalse(imports.moveOut(waiting, DEST, restoreFlow(new ArrayList<>(), false)::restore).join());
        assertEquals(LocationKind.COOP, index.get(gone).location().kind());
        assertEquals(0L, index.get(gone).generation());
        assertFalse(imports.pendingAt(WORLD, 40, 64, 9), "not tried again in this server run");
        CoopImportedResidents afterRestart = new CoopImportedResidents(index);
        assertTrue(afterRestart.pendingAt(WORLD, 40, 64, 9));
        assertTrue(afterRestart.moveOut(afterRestart.withoutCoop(goneSite).get(0), DEST,
                restoreFlow(new ArrayList<>(), true)::restore).join());
        assertEquals(LocationKind.RELEASED, index.get(gone).location().kind());
    }
}
