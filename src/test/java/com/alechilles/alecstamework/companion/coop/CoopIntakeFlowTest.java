package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopIntakeFlowTest {
    private static final BsonDocument DATA = new BsonDocument("Entity", new BsonString("chicken"));
    private static final CoopIntakeFlow.Site SITE = new CoopIntakeFlow.Site("default", 4, 64, 9, 1, "coop");

    private final List<String> indexChanges = new ArrayList<>();
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis,
            (before, after) -> indexChanges.add("change"));
    private final LoadedBodies<String> loaded = new LoadedBodies<>();
    private final List<String> events = new ArrayList<>();
    private final List<SnapshotEnvelope> snapshots = new ArrayList<>();
    private final List<TameworkCoopSlotsComponent.Slot> written = new ArrayList<>();
    private final UUID owner = UUID.randomUUID();
    private boolean slotWritable = true;
    private CompanionRecord recordAtSlotWrite;

    private final CoopIntakeFlow.Coop<String> coop = new CoopIntakeFlow.Coop<>() {
        @Override
        public CompletableFuture<Boolean> writeSlot(CoopIntakeFlow.Site site, TameworkCoopSlotsComponent.Slot entry) {
            events.add("slot");
            if (entry.profileId() != null) {
                recordAtSlotWrite = index.get(entry.profileId());
            }
            if (slotWritable) {
                written.add(entry);
            }
            return CompletableFuture.completedFuture(slotWritable);
        }

        @Override
        public CompletableFuture<Boolean> takeInUnowned(CoopIntakeFlow.Site site, TameworkCoopSlotsComponent.Slot entry,
                                                        String body) {
            events.add("slot");
            events.add("remove " + body);
            written.add(entry);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void removeBody(String body) {
            events.add("remove " + body);
        }
    };

    private CoopIntakeFlow<String> flow(CompletableFuture<Void> flush) {
        return new CoopIntakeFlow<>(index, loaded, (id, envelope) -> { events.add("snapshot"); snapshots.add(envelope); },
                who -> { events.add("flush"); return flush; }, coop);
    }

    private CompanionRecord insertLive(long generation) {
        CompanionRecord r = CompanionTransitions.newLive(UUID.randomUUID(), generation,
                new CompanionTransitions.BodyFacts(UUID.randomUUID(), owner, "Alec", "Tamed_Chicken", "Clucky", "default",
                        0, 0, 0, List.of(), CompanionSummary.EMPTY));
        index.insert(r);
        loaded.put(r.profileId(), "body");
        indexChanges.clear();
        return index.get(r.profileId());
    }

    private CoopIntakeFlow.LiveIntake<String> live(CompanionRecord record) {
        return new CoopIntakeFlow.LiveIntake<>(record.profileId(), record.generation(), "body", DATA,
                CompanionSummary.EMPTY, SITE);
    }

    @Test
    void aLiveIntakeCommitsBeforeTheSlotWriteAndTheBodyRemoval() {
        CompanionRecord live = insertLive(2);

        CoopIntakeFlow.Result result = flow(CompletableFuture.completedFuture(null)).intakeLive(live(live)).join();

        assertEquals(CoopIntakeFlow.Result.TAKEN_IN, result);
        assertEquals(List.of("snapshot", "flush", "slot", "remove body"), events);
        assertEquals(LocationKind.COOP, recordAtSlotWrite.location().kind(), "committed before the slot write");
        CompanionRecord after = index.get(live.profileId());
        assertEquals(CompanionLocation.coop("default", 4, 64, 9, 1), after.location());
        assertEquals(3, after.generation());
        assertNull(after.currentNpcUuid());
        assertNull(loaded.get(live.profileId()));
        assertEquals(TameworkCoopSlotsComponent.Slot.companion(1, live.profileId(), 3), written.get(0));
        assertEquals(3, snapshots.get(0).generation());
    }

    @Test
    void theProductionWatermarkCarriesAcrossAReleaseAndTheNextIntake() {
        CompanionRecord live = insertLive(2);
        UUID id = live.profileId();
        CoopIntakeFlow<String> flow = flow(CompletableFuture.completedFuture(null));
        flow.intakeLive(live(live)).join();
        assertEquals(0L, written.get(0).producedUntilMs());

        assertTrue(CoopProduction.save(index, id, 3, 5_000L));
        assertFalse(CoopProduction.save(index, id, 2, 7_000L), "a stale entry does not write the record");

        // The morning release commits LIVE; production may no longer write the record.
        CompanionRecord inCoop = index.get(id);
        index.update(id, inCoop.revision(), CompanionTransitions.restored(inCoop, "default", 0, 0, 0, UUID.randomUUID()));
        assertFalse(CoopProduction.save(index, id, 3, 9_000L));
        loaded.put(id, "body");

        flow.intakeLive(live(index.get(id))).join();

        assertEquals(5_000L, written.get(1).producedUntilMs());
    }

    @Test
    void aFailedFlushWritesNoSlotRemovesNoBodyAndLeavesTheCompanionLive() {
        CompanionRecord live = insertLive(2);

        CoopIntakeFlow.Result result = flow(CompletableFuture.failedFuture(new RuntimeException("disk")))
                .intakeLive(live(live)).join();

        assertEquals(CoopIntakeFlow.Result.COMMIT_FAILED, result);
        assertTrue(events.stream().noneMatch(e -> e.equals("slot") || e.startsWith("remove")));
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(2, after.generation());
        assertEquals("body", loaded.get(live.profileId()));
        assertEquals(2, snapshots.get(snapshots.size() - 1).generation(), "the snapshot is not newer than the record");
    }

    @Test
    void aSlotThatCannotBeWrittenUndoesTheIntakeAndKeepsTheBody() {
        CompanionRecord live = insertLive(2);
        slotWritable = false;

        CoopIntakeFlow.Result result = flow(CompletableFuture.completedFuture(null)).intakeLive(live(live)).join();

        assertEquals(CoopIntakeFlow.Result.SLOT_FAILED, result);
        assertTrue(events.stream().noneMatch(e -> e.startsWith("remove")));
        assertEquals(LocationKind.LIVE, index.get(live.profileId()).location().kind());
        assertEquals("body", loaded.get(live.profileId()));
    }

    @Test
    void anUnownedIntakeStoresTheEntityInlineAndNeverTouchesTheIndex() {
        BsonDocument entity = new BsonDocument("Components", new BsonDocument());

        CoopIntakeFlow.Result result = flow(CompletableFuture.completedFuture(null))
                .intakeUnowned("wild", entity, SITE).join();

        assertEquals(CoopIntakeFlow.Result.TAKEN_IN, result);
        assertEquals(List.of("slot", "remove wild"), events);
        assertSame(entity, written.get(0).unownedEntity());
        assertNull(written.get(0).profileId());
        assertTrue(indexChanges.isEmpty());
    }

    @Test
    void anItemIntakeConsumesTheItemOnlyAfterTheSlotIsWrittenAndRefusesAStaleItem() {
        CompanionRecord live = insertLive(0);
        CompanionRecord item = index.update(live.profileId(), live.revision(),
                CompanionTransitions.capturedToItem(live, CompanionSummary.EMPTY, owner, "Alec")).after();
        loaded.removeIfSame(live.profileId(), "body");
        CoopIntakeFlow<String> flow = flow(CompletableFuture.completedFuture(null));

        CoopIntakeFlow.Result stale = flow.intakeItem(new CoopIntakeFlow.ItemIntake(item.profileId(),
                item.generation() - 1, SITE, () -> events.add("consume"))).join();
        CoopIntakeFlow.Result taken = flow.intakeItem(new CoopIntakeFlow.ItemIntake(item.profileId(),
                item.generation(), SITE, () -> events.add("consume"))).join();

        assertEquals(CoopIntakeFlow.Result.NOT_ELIGIBLE, stale);
        assertEquals(CoopIntakeFlow.Result.TAKEN_IN, taken);
        assertEquals(List.of("flush", "slot", "consume"), events);
        CompanionRecord after = index.get(item.profileId());
        assertEquals(LocationKind.COOP, after.location().kind());
        assertEquals(item.generation() + 1, after.generation());
    }
}
