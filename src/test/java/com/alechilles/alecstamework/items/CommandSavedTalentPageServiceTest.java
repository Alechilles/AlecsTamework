package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest.Action;
import com.alechilles.alecstamework.companion.bonded.BondedTalentUpdates;
import com.alechilles.alecstamework.companion.bonded.BondedTalentUpdates.Status;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.flow.SnapshotPatch;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionRespawn;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyResolution;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.items.CommandSavedTalentPageService.SavedTalents;
import com.alechilles.alecstamework.items.CommandSavedTalentPageService.View;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talent purchases for a dead or lost ordinary companion, made in its stored snapshot. */
class CommandSavedTalentPageServiceTest {
    private static final String WOLF = "Tamed_Wolf";
    private static final String TREE = "test:wolf_talents";
    private static final RestoreFlow.Destination HERE = new RestoreFlow.Destination("default", 1, 2, 3, 0f, 0f);

    private final UUID owner = UUID.randomUUID();
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    /** Stands in for the writer: the latest queued snapshot per profile, read before the file. */
    private final Map<UUID, SnapshotEnvelope> queued = new HashMap<>();
    /** Every snapshot a talent change queued. */
    private final List<SnapshotEnvelope> written = new ArrayList<>();
    private Function<UUID, CompletableFuture<SnapshotEnvelope>> reads =
            id -> CompletableFuture.completedFuture(queued.get(id));
    private final SavedTalents talents = new SavedTalents(index::get, new BondedTalentUpdates(index,
            id -> reads.apply(id),
            snapshot -> {
                written.add(snapshot);
                queued.put(snapshot.profileId(), snapshot);
            },
            (record, request) -> null, (presented, roleId) -> tree(), () -> true));

    @Test
    void aDeadCompanionsPurchaseIsWrittenToItsSnapshotAndItsSummary() {
        CompanionRecord dead = insertDead();
        queued.put(dead.profileId(), deathSnapshot(dead, 5));
        View view = talents.load(owner, dead.profileId()).join();

        assertNotNull(view);
        // Another player's companion has no page.
        assertNull(talents.load(UUID.randomUUID(), dead.profileId()).join());

        assertEquals(Status.APPLIED, talents.change(owner, view, Action.PURCHASE, "swift").join().status());

        assertEquals(1, written.size());
        assertArrayEquals(new String[] {"swift"}, talentsOf(CompanionSnapshots.entity(written.get(0)))
                .getPurchasedTalentIds());
        assertEquals(1, index.get(dead.profileId()).summary().talentPointsSpent());
        // The page reads the purchase back.
        assertTrue(talents.load(owner, dead.profileId()).join().talents().hasPurchasedTalent("swift"));
    }

    @Test
    void aReviveBringsThePurchaseBack() {
        CompanionRecord dead = insertDead();
        queued.put(dead.profileId(), deathSnapshot(dead, 5));
        View view = talents.load(owner, dead.profileId()).join();
        assertEquals(Status.APPLIED, talents.change(owner, view, Action.PURCHASE, "swift").join().status());
        List<SnapshotEnvelope> restoredFrom = new ArrayList<>();

        assertEquals(RestoreFlow.Result.RESTORED,
                revive(restoredFrom).restore(dead.profileId(), RestoreRules.Reason.REVIVE, HERE).join());

        // What the spawner deserializes for a revive.
        BsonDocument body = SnapshotPatch.forRevive(
                CompanionRespawn.stripDocument(CompanionSnapshots.entity(restoredFrom.get(0))));
        assertArrayEquals(new String[] {"swift"}, talentsOf(body).getPurchasedTalentIds());
    }

    @Test
    void aCompanionWithNoSnapshotHasNoPageAndASpendWritesNothing() {
        CompanionRecord dead = insertDead();

        assertNull(talents.load(owner, dead.profileId()).join());
        assertEquals(Status.NO_LEVEL_DATA, talents.change(owner, viewOf(dead), Action.PURCHASE, "swift").join().status());
        assertTrue(written.isEmpty());
    }

    /** It died again after the revive, so it is dead again, but the page shows its old snapshot. */
    @Test
    void aPageOpenedBeforeAReviveCannotSpendAfterIt() {
        CompanionRecord dead = insertDead();
        queued.put(dead.profileId(), deathSnapshot(dead, 5));
        View view = talents.load(owner, dead.profileId()).join();
        assertEquals(RestoreFlow.Result.RESTORED,
                revive(new ArrayList<>()).restore(dead.profileId(), RestoreRules.Reason.REVIVE, HERE).join());
        CompanionRecord revived = index.get(dead.profileId());
        index.update(revived.profileId(), revived.revision(),
                CompanionTransitions.died(revived, CompanionSummary.EMPTY, 7L, 8L, "STARVED", null));
        queued.put(dead.profileId(), deathSnapshot(index.get(dead.profileId()), 5));

        assertEquals(Status.CONFLICT, talents.change(owner, view, Action.PURCHASE, "swift").join().status());
        assertTrue(written.isEmpty());
    }

    @Test
    void anUnreadableSnapshotHasNoPageAndDoesNotThrow() {
        CompanionRecord dead = insertDead();
        reads = id -> CompletableFuture.failedFuture(new IOException("unreadable"));

        assertNull(talents.load(owner, dead.profileId()).join());
        assertEquals(Status.FAILED, talents.change(owner, viewOf(dead), Action.PURCHASE, "swift").join().status());
        assertTrue(written.isEmpty());
    }

    /** Its old body's talents replace the stored ones when it rejoins, so a purchase would vanish. */
    @Test
    void anImportedLostCompanionStillWaitingForItsOldBodyCannotSpend() {
        CompanionRecord awaiting = CompanionRecord.builder(UUID.randomUUID(), WOLF,
                CompanionLocation.lost(LegacyBodyResolution.CAUSE_BODY_NOT_FOUND)).ownerUuid(owner).build();
        index.insert(awaiting);
        queued.put(awaiting.profileId(), deathSnapshot(awaiting, 5));

        assertNull(talents.load(owner, awaiting.profileId()).join());
        assertEquals(Status.CONFLICT, talents.change(owner, viewOf(awaiting), Action.PURCHASE, "swift").join().status());
        assertTrue(written.isEmpty());
    }

    /** A revive over the queued snapshots that records the snapshot it spawns from. */
    private RestoreFlow<String> revive(List<SnapshotEnvelope> restoredFrom) {
        return new RestoreFlow<>(index, new LoadedBodies<>(),
                id -> CompletableFuture.completedFuture(queued.get(id)),
                who -> CompletableFuture.completedFuture(null),
                (committed, snapshot, destination, reason) -> {
                    restoredFrom.add(snapshot);
                    return CompletableFuture.completedFuture(true);
                },
                (id, body) -> { }, System::currentTimeMillis, (before, after) -> null);
    }

    /** "swift" costs 1 point; "mighty" costs 2, needs level 10 and "swift". */
    private static TwTalentConfig tree() {
        TwTalentConfig config = TwTalentConfig.CODEC.decode(BsonDocument.parse("""
                { "Enabled": true, "Talents": [
                  { "Id": "swift", "PointCost": 1, "MinLevel": 1 },
                  { "Id": "mighty", "PointCost": 2, "MinLevel": 10, "RequiresTalentIds": ["swift"] }
                ] }
                """), new ExtraInfo());
        try {
            Field id = TwTalentConfig.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(config, TREE);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return config;
    }

    private CompanionRecord insertDead() {
        CompanionRecord live = CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(
                UUID.randomUUID(), owner, "Alec", WOLF, null, "default", 0, 0, 0, List.of(),
                CompanionSummary.EMPTY));
        index.insert(live);
        index.update(live.profileId(), live.revision(),
                CompanionTransitions.died(live, CompanionSummary.EMPTY, 5L, 6L, "STARVED", null));
        return index.get(live.profileId());
    }

    /** A death snapshot at the record's generation whose body is at {@code level} with nothing bought. */
    private static SnapshotEnvelope deathSnapshot(CompanionRecord record, int level) {
        BsonDocument components = new BsonDocument("Death", new BsonDocument())
                .append("TameworkLeveling", new BsonDocument("Level", new BsonInt32(level)));
        BsonDocument data = new BsonDocument("Entity", new BsonDocument("Components", components))
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(record.profileId(), CompanionSnapshots.FORMAT, record.generation(), data);
    }

    /** A page as it was read at the record's current generation. */
    private static View viewOf(CompanionRecord record) {
        return new View(record.profileId(), record.generation(), WOLF, null, null, 5, 0.0, 0.0,
                new TameworkTalentsComponent(TREE, 0, new String[0], 0L));
    }

    private static TameworkTalentsComponent talentsOf(BsonDocument entity) {
        return TameworkTalentsComponent.CODEC.decode(entity.getDocument("Components")
                .getDocument(SnapshotPatch.TALENTS), new ExtraInfo());
    }
}
