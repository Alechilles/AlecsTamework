package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.SnapshotPatch;
import com.alechilles.alecstamework.companion.flow.StoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talent purchases and resets of bonded companions through the bonded API. */
class BondedTalentUpdatesTest {
    private static final String ROSTER = "hydragon:horn";
    private static final String DRAGON = "Tamed_Dragon_Fire";
    private static final String TREE = "test:dragon_talents";

    private final UUID owner = UUID.randomUUID();
    private final long now = 1_000_000L;
    private final CompanionIndex index = new CompanionIndex(() -> now, (b, a) -> { });
    private final BondedCompanionPolicy policy = new BondedCompanionPolicy(7L, ROSTER, "hydragon:fire_dragon",
            Set.of(DRAGON), 0, 0, 600L, 30L, 120L, null, null, null,
            new BondedCompanionPolicy.FeatureFlags(true, true, true, true, true));
    private final List<SnapshotEnvelope> queued = new ArrayList<>();
    private final List<BondedCompanionChangedEvent> events = new ArrayList<>();
    private final List<UUID> liveUpdates = new ArrayList<>();
    /** What the stored snapshot holds; a test may hold the read back with {@link #heldRead}. */
    private SnapshotEnvelope stored;
    private CompletableFuture<SnapshotEnvelope> heldRead;
    private BondedTalentUpdates.Outcome liveOutcome;
    private final IndexBondedCompanionApi api = api();

    private IndexBondedCompanionApi api() {
        BondedRecords.Families families = (rosterId, roleId) -> ROSTER.equals(rosterId) && DRAGON.equals(roleId)
                ? policy : null;
        IndexBondedCompanionApi built = new IndexBondedCompanionApi(index, families,
                request -> CompletableFuture.completedFuture(RestoreFlow.Result.NOT_FOUND),
                (id, reason, cooldownUntilMs) -> CompletableFuture.completedFuture(StoreFlow.Result.NOT_FOUND),
                who -> CompletableFuture.completedFuture(null), id -> null, id -> { },
                (record, family) -> CompletableFuture.completedFuture(family), () -> now);
        built.useTalents(new BondedTalentUpdates(index,
                id -> heldRead != null ? heldRead : CompletableFuture.completedFuture(stored),
                queued::add,
                (record, request) -> {
                    liveUpdates.add(record.profileId());
                    return liveOutcome == null ? null : CompletableFuture.completedFuture(liveOutcome);
                },
                (presented, roleId) -> tree(), () -> true));
        built.subscribe(events::add);
        return built;
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

    /** "swift" bought under the tree's current allocation revision. */
    private static TameworkTalentsComponent swiftBought() {
        return new TameworkTalentsComponent(TREE, 1, new String[] {"swift"}, tree().getAllocationRevision());
    }

    private CompanionRecord insert(CompanionLocation at) {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), DRAGON, at).ownerUuid(owner)
                .rosterId(ROSTER).bonded(true).generation(4).displayName("Ember").build();
        index.insert(record);
        return index.get(record.profileId());
    }

    /** A stored companion whose snapshot holds {@code level} and, when given, {@code talents}. */
    private CompanionRecord storedAt(int level, TameworkTalentsComponent talents) {
        CompanionRecord record = insert(CompanionLocation.stored(StoredReason.BONDED));
        BsonDocument components = new BsonDocument("TameworkLeveling", new BsonDocument("Level", new BsonInt32(level)))
                .append("NPCEntity", new BsonDocument("RoleName", new BsonString(DRAGON)));
        if (talents != null) {
            components.put("TameworkTalents", TameworkTalentsComponent.CODEC.encode(talents, EmptyExtraInfo.EMPTY));
        }
        stored = new SnapshotEnvelope(record.profileId(), CompanionSnapshots.FORMAT, record.generation(),
                new BsonDocument("Entity", new BsonDocument("Components", components))
                        .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0)));
        return record;
    }

    private BondedCompanionTalentActionRequest purchase(CompanionRecord record, String talentId) {
        return new BondedCompanionTalentActionRequest("tamework.command-item", UUID.randomUUID().toString(), owner,
                ROSTER, record.profileId().toString(), record.generation(),
                BondedCompanionTalentActionRequest.Action.PURCHASE, talentId);
    }

    private BondedCompanionTalentActionRequest reset(CompanionRecord record) {
        return new BondedCompanionTalentActionRequest("tamework.command-item", UUID.randomUUID().toString(), owner,
                ROSTER, record.profileId().toString(), record.generation(),
                BondedCompanionTalentActionRequest.Action.RESET, null);
    }

    private static TameworkTalentsComponent talentsOf(SnapshotEnvelope snapshot) {
        return TameworkTalentsComponent.CODEC.decode(CompanionSnapshots.entity(snapshot).getDocument("Components")
                .getDocument(SnapshotPatch.TALENTS), new ExtraInfo());
    }

    @Test
    void aStoredCompanionsPurchaseIsWrittenToItsSnapshotAndReported() {
        CompanionRecord record = storedAt(5, null);

        BondedCompanionResult<BondedCompanionProfileView> result = api.updateTalents(purchase(record, "swift")).join();

        assertTrue(result.successful(), String.valueOf(result.reason()));
        assertEquals("swift", result.value().snapshotPresentationData().get("talents"));
        assertEquals("1", result.value().snapshotPresentationData().get("talentSpentPoints"));
        assertEquals("5", result.value().snapshotPresentationData().get("level"));
        assertEquals(TREE, result.value().snapshotPresentationData().get("talentConfigId"));
        assertEquals(record.generation(), result.value().revision());
        assertEquals(1, queued.size());
        assertArrayEquals(new String[] {"swift"}, talentsOf(queued.get(0)).getPurchasedTalentIds());
        assertEquals(1, talentsOf(queued.get(0)).getSpentPoints());
        // Everything else in the snapshot is kept, and it stays at the generation it was taken at.
        assertEquals(DRAGON, CompanionSnapshots.entity(queued.get(0)).getDocument("Components")
                .getDocument("NPCEntity").getString("RoleName").getValue());
        assertEquals(record.generation(), queued.get(0).generation());
        assertEquals(1, events.size());
        assertEquals("talents-updated", events.get(0).reason());
        assertEquals(BondedCompanionStateView.STORED, events.get(0).oldState());
        assertEquals(BondedCompanionStateView.STORED, events.get(0).newState());
    }

    @Test
    void aPurchaseTheLevelPointsOrPrerequisitesDoNotAllowChangesNothing() {
        CompanionRecord record = storedAt(5, null);

        BondedCompanionResult<BondedCompanionProfileView> tooLow = api.updateTalents(purchase(record, "mighty")).join();
        BondedCompanionResult<BondedCompanionProfileView> unknown = api.updateTalents(purchase(record, "nope")).join();

        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, tooLow.code());
        assertEquals("bonded-talent-purchase-rejected", tooLow.reason());
        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, unknown.code());
        assertTrue(queued.isEmpty());
        assertTrue(events.isEmpty());

        // Level 1 has earned no points, so even the cheapest talent is refused.
        CompanionRecord fresh = storedAt(1, null);
        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, api.updateTalents(purchase(fresh, "swift")).join().code());
        // A talent already bought is not charged twice.
        CompanionRecord owned = storedAt(5, swiftBought());
        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, api.updateTalents(purchase(owned, "swift")).join().code());
        assertTrue(queued.isEmpty());
    }

    @Test
    void aResetGivesEveryPointBack() {
        CompanionRecord record = storedAt(5, swiftBought());

        BondedCompanionResult<BondedCompanionProfileView> result = api.updateTalents(reset(record)).join();

        assertTrue(result.successful(), String.valueOf(result.reason()));
        assertEquals("", result.value().snapshotPresentationData().get("talents"));
        assertEquals("0", result.value().snapshotPresentationData().get("talentSpentPoints"));
        assertEquals(0, talentsOf(queued.get(0)).getPurchasedTalentIds().length);
        // With nothing spent there is nothing to reset.
        stored = queued.get(0);
        assertEquals("bonded-talent-reset-rejected", api.updateTalents(reset(record)).join().reason());
    }

    /** Both read the snapshot before either wrote it, so the second would drop the first one's talent. */
    @Test
    void ofTwoPurchasesPreparedFromTheSameSnapshotOnlyOneIsWritten() {
        CompanionRecord record = storedAt(5, null);
        heldRead = new CompletableFuture<>();

        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> first =
                api.updateTalents(purchase(record, "swift"));
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> second =
                api.updateTalents(purchase(record, "swift"));
        heldRead.complete(stored);

        // Which of the two the completed read runs first is not fixed; exactly one is written.
        boolean firstWon = first.join().successful();
        assertEquals(!firstWon, second.join().successful());
        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, (firstWon ? second : first).join().code());
        assertEquals(1, queued.size());
        assertEquals(1, events.size());
        // The record's summary follows the change, so the companion is listed with its spent points.
        assertEquals(1, index.get(record.profileId()).summary().talentPointsSpent());
        assertEquals(TREE, index.get(record.profileId()).summary().talentsConfigId());
    }

    /** A stored card shows what the companion had when it was stored; its talents come from the snapshot. */
    @Test
    void aStoredCompanionIsListedFromItsSummaryAndItsTalentsAreReadFromItsSnapshot() {
        CompanionRecord record = storedAt(5, swiftBought());
        index.update(record.profileId(), record.revision(), b -> b.summary(new CompanionSummary(null, null, DRAGON,
                null, 40f, 80f, null, 0.0, null, 0.0, 0.0, false, false, 0L, 0L, 0L, 0L, "test:leveling", 5, 12.0,
                112.0, 1, Map.of(), now, 0L, 0L, null, TREE, null)));

        Map<String, String> listed = api.list(owner, ROSTER).join().value().get(0).snapshotPresentationData();
        BondedTalentUpdates.Stored read =
                api.storedTalents(owner, ROSTER, record.profileId().toString()).join();

        assertEquals("5", listed.get("level"));
        assertEquals("test:leveling", listed.get("levelingConfigId"));
        assertEquals(40.0, Double.parseDouble(listed.get("currentHealth")));
        assertEquals(80.0, Double.parseDouble(listed.get("maxHealth")));
        assertEquals("1", listed.get("talentSpentPoints"));
        // The companion has no happiness or needs config, so the card gets no such bars.
        assertFalse(listed.containsKey("happiness"));
        assertFalse(listed.containsKey("hunger"));
        assertEquals(5, read.leveling().getLevel());
        assertArrayEquals(new String[] {"swift"}, read.talents().getPurchasedTalentIds());

        // Never summoned: nothing to list and nothing to read. Another owner reads nothing either.
        stored = null;
        CompanionRecord provisioned = insert(CompanionLocation.stored(StoredReason.PROVISIONED));
        assertNull(api.storedTalents(owner, ROSTER, provisioned.profileId().toString()).join());
        assertNull(api.storedTalents(UUID.randomUUID(), ROSTER, record.profileId().toString()).join());
        assertFalse(api.list(owner, ROSTER).join().value().stream()
                .filter(view -> view.profileId().equals(provisioned.profileId().toString()))
                .findFirst().orElseThrow().snapshotPresentationData().containsKey("level"));
    }

    /** A summon that commits while the snapshot is read must not have the change written behind it. */
    @Test
    void aChangePreparedWhileTheCompanionWasSummonedIsNotWritten() {
        CompanionRecord record = storedAt(5, null);
        heldRead = new CompletableFuture<>();

        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> pending =
                api.updateTalents(purchase(record, "swift"));
        assertFalse(pending.isDone());
        index.update(record.profileId(), record.revision(), b -> b.generation(record.generation() + 1)
                .location(CompanionLocation.live("default", 0, 0, 0)));
        heldRead.complete(stored);

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, pending.join().code());
        assertTrue(queued.isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void aStaleRevisionAnotherOwnerAndACompanionNeverSummonedAreRefused() {
        CompanionRecord record = storedAt(5, null);

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, api.updateTalents(
                new BondedCompanionTalentActionRequest("tamework.command-item", "k1", owner, ROSTER,
                        record.profileId().toString(), record.generation() + 1,
                        BondedCompanionTalentActionRequest.Action.PURCHASE, "swift")).join().code());
        assertEquals(BondedCompanionResultCode.NOT_OWNER, api.updateTalents(
                new BondedCompanionTalentActionRequest("tamework.command-item", "k2", UUID.randomUUID(), ROSTER,
                        record.profileId().toString(), record.generation(),
                        BondedCompanionTalentActionRequest.Action.PURCHASE, "swift")).join().code());
        // A provisioned companion has no snapshot, so no level to check the purchase against.
        stored = null;
        BondedCompanionResult<BondedCompanionProfileView> provisioned =
                api.updateTalents(purchase(insert(CompanionLocation.stored(StoredReason.PROVISIONED)), "swift")).join();
        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, provisioned.code());
        assertEquals("bonded-level-data-unavailable", provisioned.reason());
        assertTrue(queued.isEmpty());
    }

    /** The body holds an active companion's talents, so its stored snapshot is left alone. */
    @Test
    void anActiveCompanionIsChangedOnItsBodyNotInItsSnapshot() {
        CompanionRecord record = insert(CompanionLocation.live("default", 0, 0, 0));
        liveOutcome = new BondedTalentUpdates.Outcome(BondedTalentUpdates.Status.APPLIED,
                swiftBought(), 6, null);

        BondedCompanionResult<BondedCompanionProfileView> result = api.updateTalents(purchase(record, "swift")).join();

        assertTrue(result.successful(), String.valueOf(result.reason()));
        assertEquals(List.of(record.profileId()), liveUpdates);
        assertEquals("swift", result.value().snapshotPresentationData().get("talents"));
        assertEquals("6", result.value().snapshotPresentationData().get("level"));
        assertTrue(queued.isEmpty());
        assertEquals(BondedCompanionStateView.ACTIVE, events.get(0).newState());

        // Active with no loaded body (its chunk is unloaded): nothing can be changed safely.
        liveOutcome = null;
        assertEquals(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                api.updateTalents(purchase(record, "swift")).join().code());
    }

    /** An imported stored companion whose format 0 state holds {@code level} and, when given, {@code talents}. */
    private CompanionRecord importedAt(int level, TameworkTalentsComponent talents) {
        CompanionRecord record = insert(CompanionLocation.stored(StoredReason.BONDED));
        String json = new CoopResidentStateSnapshotCodec().encode(new CoopResidentStateSnapshot(UUID.randomUUID(),
                null, -1, DRAGON, null, null, null, null, null, null, null,
                new TameworkLevelingComponent("test:leveling", level, 0.0, 0.0), null, talents, null, null,
                null, null, 50.0, 1L));
        stored = SnapshotEnvelope.importedState(record.profileId(), record.generation(), json);
        return record;
    }

    /** Before its first summon an imported companion has only its 4.x state; its talents live there. */
    @Test
    void anImportedCompanionsTalentsAreReadFromAndBoughtIntoItsStateSnapshot() {
        CompanionRecord record = importedAt(12, swiftBought());

        BondedTalentUpdates.Stored read = api.storedTalents(owner, ROSTER, record.profileId().toString()).join();
        BondedCompanionResult<BondedCompanionProfileView> result = api.updateTalents(purchase(record, "mighty")).join();

        assertEquals(12, read.leveling().getLevel());
        assertArrayEquals(new String[] {"swift"}, read.talents().getPurchasedTalentIds());
        assertTrue(result.successful(), String.valueOf(result.reason()));
        assertEquals(1, queued.size());
        // The snapshot stays an imported state, so the first summon still restores everything else in it.
        assertEquals(SnapshotEnvelope.FORMAT_IMPORTED_STATE, queued.get(0).format());
        assertEquals(record.generation(), queued.get(0).generation());
        CoopResidentStateSnapshot after = new CoopResidentStateSnapshotCodec()
                .decode(queued.get(0).importedStateJson()).snapshotOrNull();
        assertEquals(Set.of("swift", "mighty"), Set.of(after.talents().getPurchasedTalentIds()));
        assertEquals(3, after.talents().getSpentPoints());
        assertEquals(12, after.leveling().getLevel());
        assertEquals(50.0, after.healthPercent());
    }
}
