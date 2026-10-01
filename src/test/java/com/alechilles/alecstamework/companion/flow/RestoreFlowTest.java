package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.admission.ProviderAdmission;
import com.alechilles.alecstamework.companion.index.DomainClaim;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreFlowTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final LoadedBodies<String> loaded = new LoadedBodies<>();
    private final List<String> events = new ArrayList<>();
    private final RestoreFlow.Destination there = new RestoreFlow.Destination("other", 1, 2, 3, 0f, 0f);
    private BiFunction<CompanionRecord, CompanionRecord, CompanionAdmission.Refusal> admission = (b, a) -> null;

    private CompanionRecord insertLive() {
        CompanionRecord r = CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(
                UUID.randomUUID(), UUID.randomUUID(), "Alec", "Tamed_Sheep", null, "default", 0, 0, 0, List.of(),
                CompanionSummary.EMPTY));
        index.insert(r);
        return index.get(r.profileId());
    }

    private static SnapshotEnvelope snapshot(CompanionRecord r) {
        BsonDocument data = new BsonDocument("Entity", new BsonDocument("Components", new BsonDocument()))
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(r.profileId(), CompanionSnapshots.FORMAT, r.generation(), data);
    }

    private RestoreFlow<String> flow(CompletableFuture<Void> flush, boolean spawnOk) {
        return flow(id -> CompletableFuture.completedFuture(snapshot(index.get(id))), flush, spawnOk);
    }

    private RestoreFlow<String> flow(Function<UUID, CompletableFuture<SnapshotEnvelope>> snapshots,
                                     CompletableFuture<Void> flush, boolean spawnOk) {
        return new RestoreFlow<>(index, loaded, snapshots,
                owner -> { events.add("flush"); return flush; },
                (committed, snap, dest, reason) -> { events.add("spawn gen" + committed.generation());
                    return CompletableFuture.completedFuture(spawnOk); },
                (id, body) -> events.add("remove " + body),
                System::currentTimeMillis, admission);
    }

    @Test
    void aRecallCommitsBeforeItRemovesTheOldBodyAndSpawnsTheNewOne() {
        CompanionRecord live = insertLive();
        loaded.put(live.profileId(), "old-body");

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there).join();

        assertEquals(RestoreFlow.Result.RESTORED, result);
        assertEquals(List.of("flush", "remove old-body", "spawn gen1"), events);
        CompanionRecord after = index.get(live.profileId());
        assertEquals("other", after.location().world());
        assertEquals(1, after.generation());
        assertEquals(null, loaded.get(live.profileId()), "the old body is no longer registered");
    }

    @Test
    void aFailedFlushChangesNothingLive() {
        CompanionRecord live = insertLive();
        loaded.put(live.profileId(), "old-body");

        RestoreFlow.Result result = flow(CompletableFuture.failedFuture(new RuntimeException("disk")), true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there).join();

        assertEquals(RestoreFlow.Result.COMMIT_FAILED, result);
        assertEquals(List.of("flush"), events, "no removal and no spawn");
        assertEquals("default", index.get(live.profileId()).location().world());
        assertEquals("old-body", loaded.get(live.profileId()), "the old body is registered again");
    }

    @Test
    void aChangeWhileTheSnapshotIsReadWinsAndNothingIsCommitted() {
        CompanionRecord live = insertLive();
        CompletableFuture<SnapshotEnvelope> read = new CompletableFuture<>();
        CompletableFuture<RestoreFlow.Result> pending = flow(id -> read, CompletableFuture.completedFuture(null), true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there);
        index.update(live.profileId(), live.revision(),
                CompanionTransitions.died(live, CompanionSummary.EMPTY, 5L, 6L, "PLAYER", null));

        read.complete(snapshot(live));

        assertEquals(RestoreFlow.Result.CONFLICT, pending.join());
        assertTrue(events.isEmpty(), "no flush, no removal and no spawn");
        assertEquals(LocationKind.DEAD, index.get(live.profileId()).location().kind());
    }

    @Test
    void aChangeAfterTheCommitWinsRemovesTheStaleOldBodyAndNothingSpawns() {
        CompanionRecord live = insertLive();
        loaded.put(live.profileId(), "old-body");
        CompletableFuture<Void> flush = new CompletableFuture<>();
        CompletableFuture<RestoreFlow.Result> pending = flow(flush, true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there);
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(),
                CompanionTransitions.died(committed, CompanionSummary.EMPTY, 5L, 6L, "PLAYER", null));

        flush.complete(null);

        assertEquals(RestoreFlow.Result.CONFLICT, pending.join());
        assertEquals(List.of("flush", "remove old-body"), events, "the stale body is removed and nothing spawns");
        assertEquals(LocationKind.DEAD, index.get(live.profileId()).location().kind());
    }

    @Test
    void aRestorePastAPopulationLimitIsRefusedWithoutAnyChange() {
        CompanionRecord live = insertLive();
        loaded.put(live.profileId(), "old-body");
        admission = (b, a) -> CompanionAdmission.Refusal.OWNED;

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there).join();

        assertEquals(RestoreFlow.Result.OWNED_LIMIT, result);
        assertTrue(events.isEmpty(), "no flush, no removal and no spawn");
        assertEquals(live, index.get(live.profileId()));
        assertEquals("old-body", loaded.get(live.profileId()), "the old body stays registered");
    }

    @Test
    void aFailedSpawnPutsTheRecordBack() {
        CompanionRecord live = insertLive();

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), false)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there).join();

        assertEquals(RestoreFlow.Result.SPAWN_FAILED, result);
        CompanionRecord after = index.get(live.profileId());
        assertEquals("default", after.location().world());
    }

    @Test
    void aReleaseToANewOwnerMovesTheRecord() {
        CompanionRecord live = insertLive();
        UUID releaser = UUID.randomUUID();
        index.update(live.profileId(), live.revision(), b -> b.location(CompanionLocation.item()).generation(1).currentNpcUuid(null));
        CompanionRecord item = index.get(live.profileId());

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), true).restore(
                RestoreFlow.Request.of(item.profileId(), RestoreRules.Reason.RELEASE, there)
                        .withGeneration(1).withOwner(new RestoreFlow.Owner(releaser, "Bo"))).join();

        assertEquals(RestoreFlow.Result.RESTORED, result);
        CompanionRecord after = index.get(item.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(releaser, after.ownerUuid());
        assertEquals(2, after.generation());
    }

    @Test
    void aFailedFlushOfTheOldOwnerRevertsTheOwnerChange() {
        CompanionRecord live = insertLive();
        UUID oldOwner = live.ownerUuid();
        index.update(live.profileId(), live.revision(), b -> b.location(CompanionLocation.item()).generation(1).currentNpcUuid(null));
        CompanionRecord item = index.get(live.profileId());
        RestoreFlow<String> flow = new RestoreFlow<>(index, loaded,
                id -> CompletableFuture.completedFuture(snapshot(index.get(id))),
                owner -> { events.add("flush"); return owner != null && owner.equals(oldOwner)
                        ? CompletableFuture.failedFuture(new RuntimeException("disk"))
                        : CompletableFuture.completedFuture(null); },
                (committed, snap, dest, reason) -> { events.add("spawn"); return CompletableFuture.completedFuture(true); },
                (id, body) -> events.add("remove " + body),
                System::currentTimeMillis, (b, a) -> null);

        RestoreFlow.Result result = flow.restore(RestoreFlow.Request.of(item.profileId(), RestoreRules.Reason.RELEASE, there)
                .withOwner(new RestoreFlow.Owner(UUID.randomUUID(), "Bo"))).join();

        assertEquals(RestoreFlow.Result.COMMIT_FAILED, result);
        assertEquals(List.of("flush", "flush"), events, "no live effect after a failed flush");
        CompanionRecord after = index.get(item.profileId());
        assertEquals(oldOwner, after.ownerUuid());
        assertEquals(1, after.generation());
        assertEquals(LocationKind.ITEM, after.location().kind());
    }

    @Test
    void aStaleItemIsRefusedWithoutAnyLiveEffect() {
        CompanionRecord live = insertLive();
        index.update(live.profileId(), live.revision(), b -> b.location(CompanionLocation.item()).generation(3));

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), true).restore(
                RestoreFlow.Request.of(live.profileId(), RestoreRules.Reason.RELEASE, there).withGeneration(2)).join();

        assertEquals(RestoreFlow.Result.STALE, result);
        assertTrue(events.isEmpty());
    }

    @Test
    void aRestoreThatLeavesNoOwnerSpawnsAnUntrackedBodyAndTombstonesTheRecord() {
        CompanionRecord live = insertLive();
        index.update(live.profileId(), live.revision(), b -> b.location(CompanionLocation.item()).generation(1));

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), true).restore(
                RestoreFlow.Request.of(live.profileId(), RestoreRules.Reason.RELEASE, there)
                        .withOwner(new RestoreFlow.Owner(null, null))).join();

        assertEquals(RestoreFlow.Result.RESTORED, result);
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.RELEASED, after.location().kind());
        assertEquals(CompanionTransitions.CAUSE_RELEASED_UNOWNED, after.location().cause());
        // The record moved from the owner's file to the unowned file: both are flushed before the spawn.
        assertEquals(List.of("flush", "flush", "spawn gen2"), events);
    }

    @Test
    void aTimedSummonCarriesItsTimer() {
        CompanionRecord live = insertLive();
        index.update(live.profileId(), live.revision(),
                b -> b.location(CompanionLocation.stored(StoredReason.TIMED)).generation(1));

        flow(CompletableFuture.completedFuture(null), true).restore(
                RestoreFlow.Request.of(live.profileId(), RestoreRules.Reason.SUMMON, there).withSummonedUntil(5_000)).join();

        assertEquals(5_000, index.get(live.profileId()).summonedUntilMs());
    }

    private static final String PASTURE = "runeteria:husbandry_deployable";

    private static PopulationAdmissionProviderDecision allowPasture(int weight, int limit) {
        return new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.ALLOW, "ok",
                Set.of(new PopulationDomainClaim(PASTURE, weight, false, true)), Map.of(PASTURE, limit), 1L, 0L);
    }

    /** A flow whose every role is managed by a provider that answers with {@code decision}. */
    private RestoreFlow<String> managedFlow(CompletableFuture<PopulationAdmissionProviderDecision> decision) {
        ProviderAdmission providers = new ProviderAdmission(
                role -> new ProviderAdmission.Managed("husbandry", 1, "husbandry", "sheep", Set.of("sheep"),
                        "husbandry.sheep", 1, 0L),
                request -> { events.add("provider"); return decision; });
        CompanionAdmissionGate.Check domainLimits = (before, after, provided) -> {
            CompanionAdmission.DomainRefusal refusal = CompanionAdmission.checkDomains(
                    index.fileRecords(after.ownerUuid()), before, after, provided);
            return refusal == null ? null
                    : new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED, refusal.messageKey());
        };
        return new RestoreFlow<>(index, loaded, id -> CompletableFuture.completedFuture(snapshot(index.get(id))),
                owner -> { events.add("flush"); return CompletableFuture.completedFuture(null); },
                (committed, snap, dest, reason) -> { events.add("spawn gen" + committed.generation());
                    return CompletableFuture.completedFuture(true); },
                (id, body) -> events.add("remove " + body),
                System::currentTimeMillis, providers, domainLimits);
    }

    private CompanionRecord insertItem() {
        CompanionRecord live = insertLive();
        index.update(live.profileId(), live.revision(),
                b -> b.location(CompanionLocation.item()).generation(1).currentNpcUuid(null));
        return index.get(live.profileId());
    }

    @Test
    void anAllowedRestoreWaitsForTheProviderAndStoresItsClaimsOnTheRecord() {
        CompanionRecord item = insertItem();
        CompletableFuture<PopulationAdmissionProviderDecision> decision = new CompletableFuture<>();

        CompletableFuture<RestoreFlow.Result> pending = managedFlow(decision)
                .restore(item.profileId(), RestoreRules.Reason.RELEASE, there);

        assertEquals(List.of("provider"), events, "nothing is committed before the provider answers");
        assertEquals(item, index.get(item.profileId()));

        decision.complete(allowPasture(2, 2));

        assertEquals(RestoreFlow.Result.RESTORED, pending.join());
        CompanionRecord after = index.get(item.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(List.of(new DomainClaim(PASTURE, 2, false, true)), after.domainClaims());
        assertEquals(List.of("provider", "flush", "spawn gen2"), events);
    }

    @Test
    void aDeniedOrUnavailableProviderChangesNothingAndCarriesItsMessageKey() {
        CompanionRecord item = insertItem();

        RestoreFlow.Outcome denied = managedFlow(CompletableFuture.completedFuture(
                new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.DENY,
                        "runeteria.husbandry.levelTooLow", Set.of(), Map.of(), 1L, 0L)))
                .restoreOutcome(RestoreFlow.Request.of(item.profileId(), RestoreRules.Reason.RELEASE, there)).join();
        RestoreFlow.Outcome unavailable = managedFlow(CompletableFuture.completedFuture(
                PopulationAdmissionProviderDecision.unavailable("provider-timeout")))
                .restoreOutcome(RestoreFlow.Request.of(item.profileId(), RestoreRules.Reason.RELEASE, there)).join();

        assertEquals(new RestoreFlow.Outcome(RestoreFlow.Result.PROVIDER_DENIED, "runeteria.husbandry.levelTooLow"),
                denied);
        assertEquals(new RestoreFlow.Outcome(RestoreFlow.Result.PROVIDER_UNAVAILABLE,
                CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY), unavailable);
        assertEquals(List.of("provider", "provider"), events, "no flush, no removal and no spawn");
        assertEquals(item, index.get(item.profileId()));
    }

    @Test
    void aDomainLimitFilledWhileTheProviderWasAskedRefusesUnderTheLock() {
        CompanionRecord item = insertItem();
        CompletableFuture<PopulationAdmissionProviderDecision> decision = new CompletableFuture<>();
        CompletableFuture<RestoreFlow.Outcome> pending = managedFlow(decision)
                .restoreOutcome(RestoreFlow.Request.of(item.profileId(), RestoreRules.Reason.RELEASE, there));
        // Another of the owner's companions takes the last pasture slot before the provider answers.
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Tamed_Sheep", CompanionLocation.live("other", 0, 0, 0))
                .ownerUuid(item.ownerUuid()).domainClaims(List.of(new DomainClaim(PASTURE, 1, false, true))).build());

        decision.complete(allowPasture(1, 1));

        assertEquals(new RestoreFlow.Outcome(RestoreFlow.Result.PROVIDER_DENIED,
                CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY), pending.join());
        assertEquals(List.of("provider"), events, "no flush, no removal and no spawn");
        assertEquals(item, index.get(item.profileId()));
    }
}
