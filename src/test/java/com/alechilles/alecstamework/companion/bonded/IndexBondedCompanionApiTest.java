package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionCaptureEvidenceView;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionData;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.admission.ProviderAdmission;
import com.alechilles.alecstamework.companion.flow.CaptureFlow;
import com.alechilles.alecstamework.companion.flow.CompanionBodyLifecycle;
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
import java.util.HashSet;
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
    /** Profiles with no stored snapshot, as a provisioned companion is before its first summon. */
    private final Set<UUID> noSnapshot = new HashSet<>();
    /** Profiles whose snapshot exists but cannot be read. */
    private final Set<UUID> unreadableSnapshot = new HashSet<>();
    /** Spawns that were handed no snapshot and so built the body from the record's role. */
    private final List<String> spawnedFromRole = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final List<UUID> snapshotDeletes = new ArrayList<>();
    private final Map<UUID, CompletableFuture<SnapshotEnvelope>> heldSnapshots = new HashMap<>();
    private long now = 1_000_000L;
    private boolean spawnOk = true;
    private boolean releasedOnceSpawned;
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
                        : unreadableSnapshot.contains(id)
                        ? CompletableFuture.failedFuture(new java.io.IOException("unreadable snapshot"))
                        : CompletableFuture.completedFuture(noSnapshot.contains(id) ? null : snapshot(id)),
                who -> flush,
                (committed, snapshot, destination, reason) -> {
                    spawned.add(committed.profileId());
                    if (releasedOnceSpawned) {
                        index.update(committed.profileId(), committed.revision(),
                                CompanionTransitions.released(committed));
                    }
                    if (snapshot == null && spawnOk) {
                        // As the real spawner: a body built from the role is snapshotted once added.
                        spawnedFromRole.add(committed.roleId());
                        noSnapshot.remove(committed.profileId());
                    }
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
    void aReviveThatBroughtTheCompanionBackKeepsThePriceEvenWhenItsViewCannotBeBuilt() {
        CompanionRecord dead = dead();
        Purse purse = new Purse(3);
        // The body is added, and an admin release lands before the result is put together.
        releasedOnceSpawned = true;

        BondedCompanionResult<BondedCompanionProfileView> result =
                api.revive(new BondedCompanionReviveRequest(action(dead, purse), 7L)).join();

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, result.code());
        assertEquals(List.of(dead.profileId()), spawned);
        assertEquals(0, purse.gems, "the companion came back, so the price is not returned");
    }

    @Test
    void anOldAgeDeathAndAReleaseOfAnActiveCompanionAreToldApart() {
        CompanionRecord old = insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        CompanionRecord culled = insert(DRAGON, CompanionLocation.live(WORLD, 0, 0, 0));
        List<String> reasons = new ArrayList<>();
        api.subscribe(event -> reasons.add(event.reason()));

        index.update(old.profileId(), old.revision(),
                CompanionTransitions.released(old, CompanionBodyLifecycle.CAUSE_OLD_AGE));
        // An owner or admin release writes no cause.
        index.update(culled.profileId(), culled.revision(), CompanionTransitions.released(culled));

        assertEquals(List.of("old_age", "released"), reasons);
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

    private static BondedCompanionPolicy dragons(int maximumOwned, BondedCompanionPolicy.FeatureFlags features) {
        return new BondedCompanionPolicy(7L, ROSTER, "hydragon:fire_dragon", Set.of(DRAGON), maximumOwned, 0,
                600L, 30L, 120L, null, null, null, features);
    }

    private BondedCompanionProvisionRequest provisionRequest(String key) {
        return new BondedCompanionProvisionRequest("hydragon", key, owner, ROSTER, DRAGON, "Ember", "Dragon", null,
                Map.of(), "hydragon:fire_dragon");
    }

    /** A provisioned companion, with no snapshot stored for it. */
    private CompanionRecord provisioned(String key) {
        BondedCompanionResult<BondedCompanionProfileView> result = api.provision(provisionRequest(key)).join();
        assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
        UUID id = UUID.fromString(result.value().profileId());
        noSnapshot.add(id);
        return index.get(id);
    }

    @Test
    void aProvisionedCompanionGetsItsBodyFromItsRoleOnceAndFromItsSnapshotAfterThat() {
        List<BondedCompanionChangedEvent> events = new ArrayList<>();
        api.subscribe(events::add);

        CompanionRecord provisioned = provisioned("soul-bond-1");

        assertEquals(StoredReason.PROVISIONED, provisioned.location().reason());
        assertEquals(List.of(new BondedCompanionChangedEvent(provisioned.profileId().toString(), owner, ROSTER,
                null, BondedCompanionStateView.STORED, 0L, "provisioned")), events);
        assertTrue(api.list(owner, ROSTER).join().value().get(0).summonAvailable());

        BondedCompanionResult<BondedCompanionProfileView> first = api.summon(action(provisioned)).join();

        assertEquals(BondedCompanionResultCode.SUCCESS, first.code());
        assertEquals(BondedCompanionStateView.ACTIVE, first.value().state());
        assertEquals(now + 600_000L, first.value().activeLease().expiresAtMs());
        assertEquals(List.of(DRAGON), spawnedFromRole);

        loaded.put(provisioned.profileId(), "body");
        assertEquals(BondedCompanionResultCode.SUCCESS, api.store(action(index.get(provisioned.profileId()))).join().code());
        assertEquals(StoredReason.BONDED, index.get(provisioned.profileId()).location().reason());
        now += 30_000L;

        assertEquals(BondedCompanionResultCode.SUCCESS,
                api.summon(action(index.get(provisioned.profileId()))).join().code());
        assertEquals(List.of(DRAGON), spawnedFromRole, "the second summon restores the snapshot");
        assertEquals(2, spawned.size());
    }

    @Test
    void aFirstSummonWhoseBodyIsNotAddedLeavesTheCompanionProvisionedAndSummonableAgain() {
        CompanionRecord provisioned = provisioned("soul-bond-1");
        spawnOk = false;

        assertRefused(BondedCompanionResultCode.WORLD_UNAVAILABLE, "bonded-projection-placement-unavailable",
                api.summon(action(provisioned)).join());

        CompanionRecord after = index.get(provisioned.profileId());
        assertEquals(StoredReason.PROVISIONED, after.location().reason());
        assertEquals(provisioned.generation(), after.generation());
        assertNull(after.currentNpcUuid());
        assertEquals(0, liveCount());

        spawnOk = true;
        assertEquals(BondedCompanionResultCode.SUCCESS, api.summon(action(after)).join().code());
        assertEquals(List.of(DRAGON), spawnedFromRole);
    }

    @Test
    void aCompanionLeftWithNoBodyAndNoSnapshotByAStopDuringItsFirstSummonIsBuiltFromItsRoleAgain() {
        CompanionRecord provisioned = provisioned("soul-bond-1");
        // The first summon was committed, then the server stopped before the first snapshot.
        index.update(provisioned.profileId(), provisioned.revision(), b -> b.generation(1L)
                .location(CompanionLocation.lost("REMOVED")));
        CompanionRecord lost = index.get(provisioned.profileId());

        assertEquals(BondedCompanionResultCode.SUCCESS, api.summon(action(lost)).join().code());

        assertEquals(List.of(DRAGON), spawnedFromRole);
        assertEquals(LocationKind.LIVE, index.get(lost.profileId()).location().kind());
    }

    @Test
    void aSnapshotThatCannotBeReadIsNeverReplacedByABodyFromTheRole() {
        CompanionRecord provisioned = provisioned("soul-bond-1");
        unreadableSnapshot.add(provisioned.profileId());

        assertRefused(BondedCompanionResultCode.INTERNAL_FAILURE, "bonded-snapshot-invalid",
                api.summon(action(provisioned)).join());

        assertTrue(spawned.isEmpty());
        assertEquals(provisioned, index.get(provisioned.profileId()));
    }

    @Test
    void aProvisionedCompanionWithNoNameIsNamedAfterItsSpecies() {
        BondedCompanionResult<BondedCompanionProfileView> result = api.provision(new BondedCompanionProvisionRequest(
                "hydragon", "soul-bond-1", owner, ROSTER, DRAGON, null, "Miniwyvern", null, Map.of())).join();

        assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
        assertEquals("Miniwyvern", result.value().displayName());
    }

    @Test
    void aProvisionCountsAgainstTheBuiltInOwnedCapsOnceTheyAreGiven() {
        CompanionAdmission.Refusal[] refusal = {CompanionAdmission.Refusal.OWNED};
        api.useBuiltInCaps((before, after, provided) ->
                refusal[0] == null ? null : CompanionAdmissionGate.Denial.of(refusal[0]));

        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-owned_capacity_reached",
                api.provision(provisionRequest("k1")).join());
        refusal[0] = CompanionAdmission.Refusal.GROUP_OWNED;
        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-owned_capacity_reached",
                api.provision(provisionRequest("k1")).join());
        assertTrue(index.fileRecords(owner).isEmpty());

        refusal[0] = null;
        assertEquals(BondedCompanionResultCode.SUCCESS, api.provision(provisionRequest("k1")).join().code());
    }

    @Test
    void aRepeatedProvisionRequestReturnsTheCompanionTheFirstOneMade() {
        CompanionRecord first = provisioned("soul-bond-1");
        flush = new CompletableFuture<>();

        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> repeated =
                api.provision(provisionRequest("soul-bond-1"));

        assertFalse(repeated.isDone(), "the repeat waits for the owner file like the first request");
        flush.complete(null);
        BondedCompanionResult<BondedCompanionProfileView> again = repeated.join();

        assertEquals(BondedCompanionResultCode.SUCCESS, again.code());
        assertEquals(first.profileId().toString(), again.value().profileId());
        assertEquals(1, index.fileRecords(owner).size());
        assertEquals("Ember", again.value().displayName());
    }

    @Test
    void theSameProvisionRequestAfterAnAbandonMakesANewCompanion() {
        CompanionRecord first = provisioned("soul-bond-1");
        assertEquals(BondedCompanionResultCode.SUCCESS, api.abandon(action(first)).join().code());

        BondedCompanionResult<BondedCompanionProfileView> again = api.provision(provisionRequest("soul-bond-1")).join();

        assertEquals(BondedCompanionResultCode.SUCCESS, again.code());
        assertFalse(first.profileId().toString().equals(again.value().profileId()));
        assertEquals(BondedCompanionStateView.STORED, again.value().state());
        assertEquals(1, api.list(owner, ROSTER).join().value().size());
        // And that new companion is the one a further repeat returns.
        assertEquals(again.value().profileId(), api.provision(provisionRequest("soul-bond-1")).join().value().profileId());
    }

    @Test
    void aProvisionIsRefusedWithItsReasonAndAddsNothing() {
        policy = dragons(1, ALL);
        stored();

        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-owned_capacity_reached",
                api.provision(provisionRequest("k1")).join());

        policy = dragons(0, new BondedCompanionPolicy.FeatureFlags(true, false, true, true, true));
        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-feature_disabled",
                api.provision(provisionRequest("k2")).join());

        policy = dragons(0, ALL);
        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-role_not_allowed",
                api.provision(new BondedCompanionProvisionRequest("hydragon", "k3", owner, ROSTER, "Tamed_Sheep",
                        null, null, null, Map.of())).join());
        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-role_not_allowed",
                api.provision(new BondedCompanionProvisionRequest("hydragon", "k4", owner, ROSTER, DRAGON,
                        null, null, null, Map.of(), "hydragon:other_family")).join());
        assertRefused(BondedCompanionResultCode.VALIDATION_FAILED, "bonded-request-invalid",
                api.provision(new BondedCompanionProvisionRequest("tamework", "k5", owner, ROSTER, DRAGON,
                        null, null, null, Map.of())).join());
        assertEquals(1, index.fileRecords(owner).size());
    }

    @Test
    void aProvisionThatIsNotWrittenIsWithdrawnAndCanBeRepeated() {
        flush = CompletableFuture.failedFuture(new IllegalStateException("disk full"));

        assertRefused(BondedCompanionResultCode.INTERNAL_FAILURE, "bonded-operation-failed",
                api.provision(provisionRequest("soul-bond-1")).join());
        assertTrue(api.list(owner, ROSTER).join().value().isEmpty());

        flush = CompletableFuture.completedFuture(null);
        assertEquals(BondedCompanionResultCode.SUCCESS, api.provision(provisionRequest("soul-bond-1")).join().code());
        assertEquals(1, api.list(owner, ROSTER).join().value().size());
    }

    @Test
    void theEvidenceOfACaptureIntoStorageIsFoundByItsSourceNpcUntilTheCompanionIsAbandoned() {
        UUID sourceNpc = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        List<BondedCompanionChangedEvent> events = new ArrayList<>();
        api.subscribe(events::add);
        CaptureFlow<String> captures = new CaptureFlow<>(index, loaded, (id, envelope) -> { }, who -> flush,
                ProviderAdmission.none(),
                BondedAdmission.withFamilyCaps((before, after, provided) -> null, index::fileRecords, families));
        CompanionTransitions.BodyFacts wild = new CompanionTransitions.BodyFacts(sourceNpc, null, null,
                "Dragon_Fire", null, WORLD, 0, 0, 0, List.of(), CompanionSummary.EMPTY);

        CaptureFlow.Outcome outcome = captures.capture(new CaptureFlow.Capture<>(null, 0L, "wild", wild, owner,
                "Alec", BODY, new CaptureFlow.BondedTarget(ROSTER, DRAGON, BondedCaptureEvidence.toJson(
                        new BondedCompanionCaptureEvidenceView(attempt, attempt, owner, ROSTER, "hydragon:fire_dragon",
                                sourceNpc, "unassigned", DRAGON, "tamework", attempt.toString(),
                                "Draconic_Stone", "HyDragonDraconicStone", 3L, null, -1L,
                                CaptureSourceConsumption.RESOLVED_ATTEMPT,
                                CaptureSuccessDisposition.STORE_BONDED_COMPANION, CaptureAttemptOutcome.CAPTURED,
                                "captured", WORLD, now)), now + 30_000L, null))).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        UUID profileId = outcome.itemRef().profileId();
        BondedCompanionResult<BondedCompanionCaptureEvidenceView> found =
                api.findCapture(owner, ROSTER, sourceNpc).join();
        assertEquals(BondedCompanionResultCode.SUCCESS, found.code());
        assertEquals(profileId.toString(), found.value().profileId());
        assertEquals(attempt, found.value().attemptId());
        assertEquals("HyDragonDraconicStone", found.value().spawnerConfigId());
        assertEquals(3L, found.value().spawnerConfigRevision());
        assertNull(found.value().capturePolicyConfigId());
        assertEquals(now, found.value().committedAtMs());
        assertEquals(List.of(new BondedCompanionChangedEvent(profileId.toString(), owner, ROSTER, null,
                BondedCompanionStateView.STORED, 0L, "stored")), events);
        // The captured companion is an ordinary stored one: listed, and summoned from its snapshot
        // once the family cooldown its capture started has passed.
        assertRefused(BondedCompanionResultCode.POLICY_DENIED, "bonded-transition-cooldown_active",
                api.summon(action(index.get(profileId))).join());
        now += 30_000L;
        assertEquals(BondedCompanionResultCode.SUCCESS, api.summon(action(index.get(profileId))).join().code());
        assertTrue(spawnedFromRole.isEmpty());

        assertRefused(BondedCompanionResultCode.NOT_FOUND, "bonded-capture-evidence-not-found",
                api.findCapture(owner, ROSTER, UUID.randomUUID()).join());
        assertRefused(BondedCompanionResultCode.NOT_FOUND, "bonded-capture-evidence-not-found",
                api.findCapture(UUID.randomUUID(), ROSTER, sourceNpc).join());
        // The evidence is Tamework's own entry: a public caller cannot read it as extension data.
        assertRefused(BondedCompanionResultCode.VALIDATION_FAILED, "bonded-request-invalid",
                api.getExtensionData(new BondedCompanionExtensionDataKey(owner, profileId.toString(), "tamework")).join());

        api.abandon(action(index.get(profileId))).join();
        assertRefused(BondedCompanionResultCode.NOT_FOUND, "bonded-capture-evidence-not-found",
                api.findCapture(owner, ROSTER, sourceNpc).join());
    }

    @Test
    void anAbandonThatLandsWhileASummonRunsWinsAndNothingSpawns() {
        CompanionRecord stored = stored();
        heldSnapshots.put(stored.profileId(), new CompletableFuture<>());
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> summon = api.summon(action(stored));

        assertEquals(BondedCompanionResultCode.SUCCESS, api.abandon(action(stored)).join().code());
        heldSnapshots.get(stored.profileId()).complete(snapshot(stored.profileId()));

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT, summon.join().code());
        assertTrue(spawned.isEmpty());
        assertEquals(LocationKind.RELEASED, index.get(stored.profileId()).location().kind());
        assertEquals(0, liveCount());
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
        assertEquals(BondedCompanionResultCode.VALIDATION_FAILED, api.compareAndSetExtensionData(
                new BondedCompanionExtensionDataUpdate("hydragon", "op-0", key, "{\"xp\":",
                        BondedCompanionExtensionDataUpdate.MISSING_REVISION)).join().code());
        assertTrue(index.get(stored.profileId()).extensions().isEmpty(), "a payload that is not JSON is not stored");

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
