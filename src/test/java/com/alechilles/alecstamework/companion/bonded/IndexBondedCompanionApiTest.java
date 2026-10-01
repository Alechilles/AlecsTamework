package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionData;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.admission.ProviderAdmission;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.StoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexBondedCompanionApiTest {
    private static final String ROSTER = "hydragon:horn";
    private static final String DRAGON = "Tamed_Dragon_Fire";
    private static final String WORLD = "default";
    private static final BondedCompanionPolicy.FeatureFlags ALL =
            new BondedCompanionPolicy.FeatureFlags(true, true, true, true, true);
    private static final BsonDocument BODY =
            new BsonDocument("Entity", new BsonDocument("Components", new BsonDocument()));

    private final UUID owner = UUID.randomUUID();
    private final LoadedBodies<String> loaded = new LoadedBodies<>();
    private final List<UUID> spawned = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final List<UUID> snapshotDeletes = new ArrayList<>();
    private final Map<UUID, CompletableFuture<SnapshotEnvelope>> heldSnapshots = new HashMap<>();
    private long now = 1_000_000L;
    private boolean spawnOk = true;
    private CompletableFuture<Void> flush = CompletableFuture.completedFuture(null);
    private CompletableFuture<StoreFlow.CapturedBody> captured =
            CompletableFuture.completedFuture(new StoreFlow.CapturedBody(BODY, CompanionSummary.EMPTY));
    /** 600 s session, 30 s summon cooldown, 120 s revive cooldown, 3 Gems to revive. */
    private BondedCompanionPolicy policy = dragons(0);
    private final CompanionIndex index = new CompanionIndex(() -> now, (b, a) -> { });
    private final BondedRecords.Families families = (rosterId, roleId) -> {
        if (roleId.isBlank()) {
            throw new IllegalArgumentException("roleId is required");
        }
        return ROSTER.equals(rosterId) && DRAGON.equals(roleId) ? policy : null;
    };
    private final IndexBondedCompanionApi api = api();

    private static BondedCompanionPolicy dragons(int maximumActive) {
        return new BondedCompanionPolicy(7L, ROSTER, "hydragon:fire_dragon", Set.of(DRAGON), 0, maximumActive,
                600L, 30L, 120L, null, null,
                new BondedCompanionPolicy.RevivePrice(List.of(new BondedCompanionReviveCost("Gem", 3))), ALL);
    }

    /** The real restore and store flows over an in-memory index, with fake snapshots, files and worlds. */
    private IndexBondedCompanionApi api() {
        RestoreFlow<String> restore = new RestoreFlow<>(index, loaded,
                id -> heldSnapshots.containsKey(id) ? heldSnapshots.get(id)
                        : CompletableFuture.completedFuture(snapshot(id)),
                who -> flush,
                (committed, snapshot, destination, reason) -> {
                    spawned.add(committed.profileId());
                    return CompletableFuture.completedFuture(spawnOk);
                },
                (id, body) -> removed.add(body), () -> now, ProviderAdmission.none(),
                BondedAdmission.withFamilyCaps((before, after, provided) -> null, index::fileRecords, families));
        StoreFlow<String> store = new StoreFlow<>(index, loaded,
                body -> captured,
                id -> CompletableFuture.completedFuture(snapshot(id)), (id, envelope) -> { }, who -> flush,
                (id, body) -> removed.add(body), () -> now);
        IndexBondedCompanionApi built = new IndexBondedCompanionApi(index, families, restore::restore, store::store,
                who -> flush, IndexBondedCompanionApi.bodies(loaded, removed::add), snapshotDeletes::add,
                (record, family) -> CompletableFuture.completedFuture(family), () -> now);
        index.addAfterUnlockListener(built::onChanged);
        return built;
    }

    private static SnapshotEnvelope snapshot(UUID id) {
        return new SnapshotEnvelope(id, CompanionSnapshots.FORMAT, 0L,
                BODY.clone().append("World", new BsonString(WORLD)).append("GameTimeMs", new BsonInt64(0)));
    }

    private CompanionRecord insert(String role, CompanionLocation at) {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), role, at).ownerUuid(owner)
                .rosterId(ROSTER).bonded(true).generation(4).displayName("Ember").build();
        index.insert(record);
        return index.get(record.profileId());
    }

    private CompanionRecord stored() {
        return insert(DRAGON, CompanionLocation.stored(StoredReason.BONDED));
    }

    private CompanionRecord dead() {
        CompanionRecord live = insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        index.update(live.profileId(), live.revision(),
                CompanionTransitions.died(live, CompanionSummary.EMPTY, now, now, "PLAYER", null));
        return index.get(live.profileId());
    }

    private BondedCompanionActionRequest action(CompanionRecord record, BondedCompanionActionContext.Inventory inventory) {
        return new BondedCompanionActionRequest("tamework-panel", UUID.randomUUID().toString(), owner, ROSTER,
                record.profileId().toString(), record.generation(), WORLD, new BondedCompanionActionContext(
                        new BondedCompanionPlacement(WORLD, 1, 2, 3, 0f, 0f, 0f), inventory));
    }

    private BondedCompanionActionRequest action(CompanionRecord record) {
        return action(record, null);
    }

    private long liveCount() {
        return index.fileRecords(owner).stream().filter(CompanionRecord::isDeployed).count();
    }

    @Test
    void aSummonBringsTheCompanionOutWithTheFamilySessionTimerAndTellsSubscribers() throws Exception {
        CompanionRecord stored = stored();
        List<BondedCompanionChangedEvent> events = new ArrayList<>();
        AutoCloseable subscription = api.subscribe(events::add);

        BondedCompanionResult<BondedCompanionProfileView> result = api.summon(action(stored)).join();

        assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
        assertEquals(BondedCompanionStateView.ACTIVE, result.value().state());
        assertEquals(5L, result.value().revision(), "the summon raises the generation the next action must name");
        assertEquals("5", result.value().activeLease().leaseToken());
        assertEquals(now + 600_000L, result.value().activeLease().expiresAtMs());
        assertEquals(List.of(stored.profileId()), spawned);
        assertEquals(List.of(new BondedCompanionChangedEvent(stored.profileId().toString(), owner, ROSTER,
                BondedCompanionStateView.STORED, BondedCompanionStateView.ACTIVE, 5L, "summoned")), events);

        subscription.close();
        api.store(action(index.get(stored.profileId()))).join();

        assertEquals(1, events.size(), "a closed subscription hears nothing more");
    }

    @Test
    void twoSummonsAtOnceInAFamilyWithOneActivePlaceLeaveOneBody() {
        policy = dragons(1);
        CompanionRecord first = stored();
        CompanionRecord second = stored();
        heldSnapshots.put(first.profileId(), new CompletableFuture<>());
        heldSnapshots.put(second.profileId(), new CompletableFuture<>());

        // Both pass the check made before the flow: neither is active yet.
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> a = api.summon(action(first));
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> b = api.summon(action(second));
        heldSnapshots.get(first.profileId()).complete(snapshot(first.profileId()));
        heldSnapshots.get(second.profileId()).complete(snapshot(second.profileId()));

        assertEquals(BondedCompanionResultCode.SUCCESS, a.join().code());
        assertEquals(BondedCompanionResultCode.POLICY_DENIED, b.join().code());
        assertEquals(IndexBondedCompanionApi.ACTIVE_CAPACITY, b.join().reason());
        assertEquals(List.of(first.profileId()), spawned);
        assertEquals(1, liveCount());
        assertEquals(second, index.get(second.profileId()), "the refused companion is unchanged");
    }

    @Test
    void theSameCompanionSummonedTwiceAtOnceGetsOneBody() {
        CompanionRecord stored = stored();
        heldSnapshots.put(stored.profileId(), new CompletableFuture<>());

        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> a = api.summon(action(stored));
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> b = api.summon(action(stored));
        heldSnapshots.get(stored.profileId()).complete(snapshot(stored.profileId()));

        assertEquals(Set.of(BondedCompanionResultCode.SUCCESS, BondedCompanionResultCode.REVISION_CONFLICT),
                Set.of(a.join().code(), b.join().code()));
        assertEquals(List.of(stored.profileId()), spawned);
        assertEquals(5L, index.get(stored.profileId()).generation());
    }

    @Test
    void aDeathRecordedWhileASummonRunsWinsAndNothingSpawns() {
        CompanionRecord stored = stored();
        heldSnapshots.put(stored.profileId(), new CompletableFuture<>());
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> summon = api.summon(action(stored));

        index.update(stored.profileId(), stored.revision(),
                CompanionTransitions.died(stored, CompanionSummary.EMPTY, now, now + 120_000L, "PLAYER", null));
        heldSnapshots.get(stored.profileId()).complete(snapshot(stored.profileId()));

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, summon.join().code());
        assertTrue(spawned.isEmpty());
        assertEquals(LocationKind.DEAD, index.get(stored.profileId()).location().kind());
    }

    @Test
    void aDeathRecordedWhileAnActiveCompanionIsBeingStoredWins() {
        CompanionRecord live = insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        loaded.put(live.profileId(), "body");
        StoreFlow.CapturedBody body = captured.join();
        captured = new CompletableFuture<>();
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> store = api.store(action(live));

        // The body dies on its world thread before its snapshot for the store comes back.
        index.update(live.profileId(), live.revision(),
                CompanionTransitions.died(live, CompanionSummary.EMPTY, now, now + 120_000L, "PLAYER", null));
        captured.complete(body);

        assertRefused(BondedCompanionResultCode.REVISION_CONFLICT, "bonded-store-not-committed", store.join());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.DEAD, after.location().kind());
        assertEquals(now + 120_000L, after.reviveAvailableAtMs());
        assertEquals(0L, after.summonCooldownUntilMs(), "the lost store left no cooldown behind");
    }

    @Test
    void aLostCompanionIsSummonedBackWithASessionTimer() {
        CompanionRecord lost = insert(DRAGON, CompanionLocation.lost("WORLD_REMOVED"));

        BondedCompanionResult<BondedCompanionProfileView> result = api.summon(action(lost)).join();

        assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
        assertEquals(LocationKind.LIVE, index.get(lost.profileId()).location().kind());
        assertEquals(now + 600_000L, index.get(lost.profileId()).summonedUntilMs());
        assertEquals(now + 600_000L, result.value().activeLease().expiresAtMs());
    }

    @Test
    void aSummonIsRefusedWithTheReasonThePanelMaps() {
        CompanionRecord cooling = insert(DRAGON, CompanionLocation.stored(StoredReason.BONDED));
        index.update(cooling.profileId(), cooling.revision(), b -> b.summonCooldownUntilMs(now + 1L));
        CompanionRecord provisioned = insert(DRAGON, CompanionLocation.stored(StoredReason.PROVISIONED));
        CompanionRecord otherRole = insert("Tamed_Sheep", CompanionLocation.stored(StoredReason.BONDED));
        CompanionRecord ready = stored();
        CompanionRecord live = insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        BondedCompanionActionRequest noPlacement = new BondedCompanionActionRequest("tamework-panel", "k", owner,
                ROSTER, ready.profileId().toString(), ready.generation(), WORLD);
        BondedCompanionActionRequest stale = new BondedCompanionActionRequest("tamework-panel", "k", owner, ROSTER,
                ready.profileId().toString(), 3L, WORLD, action(ready).actionContext());
        BondedCompanionActionRequest stranger = new BondedCompanionActionRequest("tamework-panel", "k",
                UUID.randomUUID(), ROSTER, ready.profileId().toString(), ready.generation(), WORLD,
                action(ready).actionContext());

        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-cooldown_active",
                api.summon(action(index.get(cooling.profileId()))).join());
        assertRefused(BondedCompanionResultCode.UNAVAILABLE, IndexBondedCompanionApi.FIRST_SUMMON_UNAVAILABLE,
                api.summon(action(provisioned)).join());
        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-role_not_allowed",
                api.summon(action(otherRole)).join());
        assertRefused(BondedCompanionResultCode.INVALID_STATE, "bonded-summon-already-live",
                api.summon(action(live)).join());
        assertRefused(BondedCompanionResultCode.WORLD_UNAVAILABLE, "bonded-placement-context-required",
                api.summon(noPlacement).join());
        assertRefused(BondedCompanionResultCode.REVISION_CONFLICT, "bonded-transition-revision_conflict",
                api.summon(stale).join());
        assertRefused(BondedCompanionResultCode.NOT_OWNER, "bonded-transition-not_owner", api.summon(stranger).join());
        assertTrue(spawned.isEmpty());
    }

    private static void assertRefused(BondedCompanionResultCode code, String reason, BondedCompanionResult<?> result) {
        assertEquals(code, result.code());
        assertEquals(reason, result.reason());
    }

    @Test
    void storingAnActiveCompanionStartsTheFamilySummonCooldown() {
        CompanionRecord live = insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        loaded.put(live.profileId(), "body");

        BondedCompanionResult<BondedCompanionProfileView> result = api.store(action(live)).join();

        CompanionRecord after = index.get(live.profileId());
        assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
        assertEquals(BondedCompanionStateView.STORED, result.value().state());
        assertEquals(StoredReason.BONDED, after.location().reason());
        assertEquals(now + 30_000L, after.summonCooldownUntilMs());
        assertFalse(result.value().summonAvailable());
        assertEquals(List.of("body"), removed);
    }

    @Test
    void aPaidReviveChargesThePriceAndBringsTheCompanionBackActive() {
        CompanionRecord dead = dead();
        Purse purse = new Purse(5);

        BondedCompanionResult<BondedCompanionProfileView> result =
                api.revive(new BondedCompanionReviveRequest(action(dead, purse), 7L)).join();

        assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
        assertEquals(BondedCompanionStateView.ACTIVE, result.value().state());
        assertEquals(now + 600_000L, index.get(dead.profileId()).summonedUntilMs());
        assertEquals(2, purse.gems);
        assertEquals(List.of(dead.profileId()), spawned);
    }

    @Test
    void aReviveThatDoesNotBringTheCompanionBackRefundsThePrice() {
        CompanionRecord dead = dead();
        Purse purse = new Purse(3);
        spawnOk = false;

        BondedCompanionResult<BondedCompanionProfileView> result =
                api.revive(new BondedCompanionReviveRequest(action(dead, purse), 7L)).join();

        assertEquals(BondedCompanionResultCode.WORLD_UNAVAILABLE, result.code());
        assertEquals(3, purse.gems);
        assertEquals(LocationKind.DEAD, index.get(dead.profileId()).location().kind());
    }

    @Test
    void aReviveTheOwnerCannotPayForChangesNothing() {
        CompanionRecord dead = dead();
        Purse purse = new Purse(2);

        BondedCompanionResult<BondedCompanionProfileView> result =
                api.revive(new BondedCompanionReviveRequest(action(dead, purse), 7L)).join();
        BondedCompanionReviveQuote quote = api.quoteRevive(action(dead, purse)).join().value();

        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-revive-payment-insufficient", result);
        assertEquals(2, purse.gems);
        assertTrue(spawned.isEmpty());
        assertEquals(dead, index.get(dead.profileId()));
        assertEquals(List.of(new BondedCompanionReviveQuote.CostLine("Gem", 3, 2)), quote.costs());
    }

    @Test
    void aReviveIntoAFullFamilyIsRefusedBeforeAnythingIsCharged() {
        policy = dragons(1);
        insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        CompanionRecord dead = dead();
        Purse purse = new Purse(3);

        BondedCompanionResult<BondedCompanionProfileView> result =
                api.revive(new BondedCompanionReviveRequest(action(dead, purse), 7L)).join();

        assertRefused(BondedCompanionResultCode.POLICY_DENIED, IndexBondedCompanionApi.ACTIVE_CAPACITY, result);
        assertEquals(3, purse.gems);
    }

    @Test
    void abandoningACompanionLeavesATombstoneRemovesItsBodyAndDeletesItsSnapshot() {
        CompanionRecord live = insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        loaded.put(live.profileId(), "body");
        BondedCompanionActionRequest stale = new BondedCompanionActionRequest("tamework-panel", "k", owner, ROSTER,
                live.profileId().toString(), 3L, WORLD);

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, api.abandon(stale).join().code());
        assertEquals(live, index.get(live.profileId()));

        assertEquals(BondedCompanionResultCode.SUCCESS, api.abandon(action(live)).join().code());

        assertEquals(LocationKind.RELEASED, index.get(live.profileId()).location().kind());
        assertNull(loaded.get(live.profileId()));
        assertEquals(List.of("body"), removed);
        assertEquals(List.of(live.profileId()), snapshotDeletes);
        assertTrue(api.list(owner, ROSTER).join().value().isEmpty());
    }

    @Test
    void theListHoldsTheRosterBondedCompanionsEvenWhenOneHasABlankRole() {
        CompanionRecord dragon = stored();
        CompanionRecord blank = insert("", CompanionLocation.stored(StoredReason.BONDED));
        index.insert(CompanionRecord.builder(UUID.randomUUID(), DRAGON, CompanionLocation.live(WORLD, 0, 0, 0))
                .ownerUuid(owner).rosterId(ROSTER).build());

        List<BondedCompanionProfileView> views = api.list(owner, ROSTER).join().value();

        assertEquals(Set.of(dragon.profileId().toString(), blank.profileId().toString()),
                Set.copyOf(views.stream().map(BondedCompanionProfileView::profileId).toList()));
        BondedCompanionProfileView unresolved = views.stream()
                .filter(view -> view.profileId().equals(blank.profileId().toString())).findFirst().orElseThrow();
        assertEquals(BondedRecords.UNRESOLVED_FAMILY_ID, unresolved.familyId());
        assertFalse(unresolved.summonAvailable());
    }

    @Test
    void extensionDataIsComparedAndSetPerNamespaceAndAReplayWaitsForTheOwnerFile() {
        CompanionRecord stored = stored();
        BondedCompanionExtensionDataKey key =
                new BondedCompanionExtensionDataKey(owner, stored.profileId().toString(), "hydragon");

        assertEquals(BondedCompanionResultCode.NOT_FOUND, api.getExtensionData(key).join().code());

        BondedCompanionExtensionData first = api.compareAndSetExtensionData(new BondedCompanionExtensionDataUpdate(
                "hydragon", "op-1", key, "{\"xp\":1}", BondedCompanionExtensionDataUpdate.MISSING_REVISION))
                .join().value();
        assertEquals(0L, first.revision());
        assertEquals("{\"xp\":1}", api.getExtensionData(key).join().value().jsonPayload());

        // The same request again: its value is current, but it still waits for the owner file.
        flush = new CompletableFuture<>();
        CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>> replay =
                api.compareAndSetExtensionData(new BondedCompanionExtensionDataUpdate(
                        "hydragon", "op-1", key, "{\"xp\":1}", BondedCompanionExtensionDataUpdate.MISSING_REVISION));
        assertFalse(replay.isDone());
        flush.complete(null);
        assertEquals(BondedCompanionResultCode.SUCCESS, replay.join().code());
        assertEquals(0L, replay.join().value().revision());

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, api.compareAndSetExtensionData(
                new BondedCompanionExtensionDataUpdate("hydragon", "op-2", key, "{\"xp\":9}",
                        BondedCompanionExtensionDataUpdate.MISSING_REVISION)).join().code());
        assertEquals(1L, api.compareAndSetExtensionData(
                new BondedCompanionExtensionDataUpdate("hydragon", "op-3", key, "{\"xp\":2}", 0L))
                .join().value().revision());
        assertEquals("{\"xp\":2}", api.getExtensionData(key).join().value().jsonPayload());
    }

    @Test
    void anExtensionWriteThatIsNotSavedIsUndone() {
        CompanionRecord stored = stored();
        BondedCompanionExtensionDataKey key =
                new BondedCompanionExtensionDataKey(owner, stored.profileId().toString(), "hydragon");
        flush = CompletableFuture.failedFuture(new RuntimeException("disk"));

        BondedCompanionResult<BondedCompanionExtensionData> result = api.compareAndSetExtensionData(
                new BondedCompanionExtensionDataUpdate("hydragon", "op-1", key, "{\"xp\":1}",
                        BondedCompanionExtensionDataUpdate.MISSING_REVISION)).join();

        assertEquals(BondedCompanionResultCode.INTERNAL_FAILURE, result.code());
        assertTrue(index.get(stored.profileId()).extensions().isEmpty());
    }

    @Test
    void publicCallersCannotUseTameworksOwnExtensionNamespace() {
        CompanionRecord stored = stored();
        BondedCompanionExtensionDataKey reserved =
                new BondedCompanionExtensionDataKey(owner, stored.profileId().toString(), "tamework");

        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, api.getExtensionData(reserved).join().code());
        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, api.compareAndSetExtensionData(
                new BondedCompanionExtensionDataUpdate("hydragon", "op-1", reserved, "{}",
                        BondedCompanionExtensionDataUpdate.MISSING_REVISION)).join().code());
        assertTrue(index.get(stored.profileId()).extensions().isEmpty());
    }

    /** A player inventory holding only Gems; a charge takes them all or nothing. */
    private static final class Purse implements BondedCompanionActionContext.Inventory {
        private int gems;

        private Purse(int gems) {
            this.gems = gems;
        }

        @Override
        public int availableQuantity(String itemId) {
            return "Gem".equals(itemId) ? gems : 0;
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt consumeExact(String operationId, String itemId, int quantity) {
            if (!"Gem".equals(itemId) || gems < quantity) {
                return null;
            }
            gems -= quantity;
            return new BondedCompanionActionContext.ChargeReceipt() {
                private boolean refunded;

                @Override
                public String operationId() {
                    return operationId;
                }

                @Override
                public boolean refund() {
                    if (!refunded) {
                        refunded = true;
                        gems += quantity;
                    }
                    return true;
                }
            };
        }
    }
}
