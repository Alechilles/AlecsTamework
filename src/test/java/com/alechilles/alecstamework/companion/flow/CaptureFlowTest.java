package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.admission.ProviderAdmission;
import com.alechilles.alecstamework.companion.bonded.BondedAdmission;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.companion.bonded.BondedRecords;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
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
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureFlowTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final LoadedBodies<String> loaded = new LoadedBodies<>();
    private final List<String> events = new ArrayList<>();
    private final List<SnapshotEnvelope> snapshots = new ArrayList<>();
    private final UUID owner = UUID.randomUUID();
    private CompanionAdmission.Refusal refusal;

    private CompanionTransitions.BodyFacts facts(UUID npcUuid) {
        return new CompanionTransitions.BodyFacts(npcUuid, owner, "Alec", "Tamed_Sheep", "Wooly", "default", 0, 0, 0,
                List.of(), CompanionSummary.EMPTY);
    }

    private CompanionRecord insertLive(long generation) {
        UUID npc = UUID.randomUUID();
        CompanionRecord r = CompanionTransitions.newLive(UUID.randomUUID(), generation, facts(npc));
        index.insert(r);
        loaded.put(r.profileId(), "body");
        return index.get(r.profileId());
    }

    private CaptureFlow<String> flow(Function<UUID, CompletableFuture<Void>> flush) {
        return new CaptureFlow<>(index, loaded, (id, envelope) -> { events.add("snapshot"); snapshots.add(envelope); },
                owner -> { events.add("flush"); return flush.apply(owner); }, (before, after) -> refusal);
    }

    private CaptureFlow<String> flow(CompletableFuture<Void> flush) {
        return flow(owner -> flush);
    }

    private static final BsonDocument DATA = new BsonDocument("Entity", new BsonString("x"));

    private CaptureFlow.Capture<String> stamped(CompanionRecord live, UUID newOwner) {
        return new CaptureFlow.Capture<>(live.profileId(), live.generation(), "body", facts(live.currentNpcUuid()),
                newOwner, newOwner == null ? null : "Bo", DATA);
    }

    @Test
    void aStampedCaptureCommitsItemUnregistersTheBodyAndFlushesBeforeReturning() {
        CompanionRecord live = insertLive(2);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null))
                .capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals(live.profileId(), outcome.itemRef().profileId());
        assertEquals(3, outcome.itemRef().generation());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.ITEM, after.location().kind());
        assertEquals(3, after.generation());
        assertNull(after.currentNpcUuid());
        assertNull(loaded.get(live.profileId()));
        assertEquals(List.of("snapshot", "flush"), events);
        assertEquals(3, snapshots.get(0).generation());
        assertEquals(CompanionSnapshots.FORMAT, snapshots.get(0).format());
    }

    @Test
    void aCaptureOfABodyRenamedSinceItsRecordLastSawItKeepsTheNewName() {
        CompanionRecord live = insertLive(2);
        CompanionTransitions.BodyFacts renamed = new CompanionTransitions.BodyFacts(live.currentNpcUuid(), owner,
                "Alec", "Tamed_Sheep", "Snowy", "default", 0, 0, 0, List.of(), CompanionSummary.EMPTY);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null)).capture(
                new CaptureFlow.Capture<>(live.profileId(), live.generation(), "body", renamed, owner, "Alec", DATA)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals("Snowy", index.get(live.profileId()).displayName());
    }

    @Test
    void aStaleStampIsRefusedAndNothingChanges() {
        CompanionRecord live = insertLive(2);
        CaptureFlow.Capture<String> stale = new CaptureFlow.Capture<>(live.profileId(), 1, "body",
                facts(live.currentNpcUuid()), owner, "Alec", DATA);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null)).capture(stale).join();

        assertEquals(CaptureFlow.Result.NOT_CAPTURABLE, outcome.result());
        assertNull(outcome.itemRef());
        assertTrue(events.isEmpty());
        assertEquals(live, index.get(live.profileId()));
        assertEquals("body", loaded.get(live.profileId()));
    }

    @Test
    void aDifferentRegisteredBodyForTheProfileRefusesTheCapture() {
        CompanionRecord live = insertLive(2);
        loaded.put(live.profileId(), "other-body");

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null))
                .capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.NOT_CAPTURABLE, outcome.result());
        assertTrue(events.isEmpty());
        assertEquals(live, index.get(live.profileId()));
        assertEquals("other-body", loaded.get(live.profileId()));
    }

    @Test
    void anEditThatKeepsTheItemHolderDuringTheFlushStillCaptures() {
        CompanionRecord live = insertLive(2);
        CompletableFuture<Void> flush = new CompletableFuture<>();
        CompletableFuture<CaptureFlow.Outcome> pending = flow(flush).capture(stamped(live, owner));
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(), b -> b.displayName("Renamed"));

        flush.complete(null);

        CaptureFlow.Outcome outcome = pending.join();
        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals(3, outcome.itemRef().generation());
        assertEquals("Renamed", index.get(live.profileId()).displayName());
    }

    @Test
    void aSnapshotQueueFailureUndoesTheCaptureWithoutFailingTheFuture() {
        CompanionRecord live = insertLive(2);
        CaptureFlow<String> flow = new CaptureFlow<>(index, loaded,
                (id, envelope) -> { throw new IllegalStateException("queue full"); },
                owner -> CompletableFuture.completedFuture(null), (before, after) -> null);

        CaptureFlow.Outcome outcome = flow.capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(2, after.generation());
        assertEquals("body", loaded.get(live.profileId()));
    }

    @Test
    void aFailedFlushLeavesTheRecordLiveAndTheBodyRegistered() {
        CompanionRecord live = insertLive(2);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.failedFuture(new RuntimeException("disk")))
                .capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        assertNull(outcome.itemRef());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(2, after.generation());
        assertEquals(live.currentNpcUuid(), after.currentNpcUuid());
        assertEquals("body", loaded.get(live.profileId()));
        SnapshotEnvelope last = snapshots.get(snapshots.size() - 1);
        assertEquals(2, last.generation(), "the snapshot is not newer than the restored record");
    }

    @Test
    void aFailedFlushOfTheOldOwnerRevertsAnOwnerChange() {
        CompanionRecord live = insertLive(0);
        UUID newOwner = UUID.randomUUID();

        CaptureFlow.Outcome outcome = flow(who -> who != null && who.equals(newOwner)
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new RuntimeException("disk")))
                .capture(stamped(live, newOwner)).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        assertEquals(List.of("snapshot", "flush", "flush", "snapshot"), events, "new owner first, then the old one");
        assertEquals(owner, index.get(live.profileId()).ownerUuid());
        assertEquals(LocationKind.LIVE, index.get(live.profileId()).location().kind());
        assertEquals("body", loaded.get(live.profileId()));
    }

    @Test
    void aChangeDuringTheFlushWinsAndTheCaptureIsAConflict() {
        CompanionRecord live = insertLive(0);
        CompletableFuture<Void> flush = new CompletableFuture<>();
        CompletableFuture<CaptureFlow.Outcome> pending = flow(flush).capture(stamped(live, owner));
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(),
                CompanionTransitions.died(committed, CompanionSummary.EMPTY, 5L, 6L, "PLAYER", null));

        flush.complete(null);

        CaptureFlow.Outcome outcome = pending.join();
        assertEquals(CaptureFlow.Result.CONFLICT, outcome.result());
        assertNull(outcome.itemRef());
        assertEquals(LocationKind.DEAD, index.get(live.profileId()).location().kind());
    }

    @Test
    void anUnstampedBodyGetsANewItemRecordAtGenerationZero() {
        UUID npc = UUID.randomUUID();
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null)).capture(capture).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals(0, outcome.itemRef().generation());
        CompanionRecord record = index.get(outcome.itemRef().profileId());
        assertNotNull(record);
        assertEquals(LocationKind.ITEM, record.location().kind());
        assertEquals("Tamed_Sheep", record.roleId());
        assertEquals("Wooly", record.displayName());
        assertEquals(owner, record.ownerUuid());
        assertEquals(0, snapshots.get(0).generation());
    }

    @Test
    void aSecondUnstampedCaptureOfTheSameNpcIsRefused() {
        UUID npc = UUID.randomUUID();
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);
        CaptureFlow<String> flow = flow(CompletableFuture.completedFuture(null));
        flow.capture(capture).join();

        CaptureFlow.Outcome second = flow.capture(capture).join();

        assertEquals(CaptureFlow.Result.NOT_CAPTURABLE, second.result());
        assertEquals(1, index.fileRecords(owner).size());
    }

    @Test
    void aFailedUnstampedCaptureLeavesATombstoneInsteadOfAnItem() {
        UUID npc = UUID.randomUUID();
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.failedFuture(new RuntimeException("disk")))
                .capture(capture).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        List<CompanionRecord> filed = index.fileRecords(owner);
        assertEquals(1, filed.size());
        assertEquals(LocationKind.RELEASED, filed.get(0).location().kind());
    }

    @Test
    void aCaptureThatWouldPassAPopulationCapChangesNothing() {
        UUID npc = UUID.randomUUID();
        refusal = CompanionAdmission.Refusal.GROUP_OWNED;
        CaptureFlow.Capture<String> wild = new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);
        CompanionRecord live = insertLive(1);

        CaptureFlow.Outcome unstamped = flow(CompletableFuture.completedFuture(null)).capture(wild).join();
        refusal = CompanionAdmission.Refusal.OWNED;
        CaptureFlow.Outcome stamped = flow(CompletableFuture.completedFuture(null))
                .capture(stamped(live, UUID.randomUUID())).join();

        assertEquals(CaptureFlow.Result.GROUP_LIMIT, unstamped.result());
        assertEquals(CaptureFlow.Result.OWNED_LIMIT, stamped.result());
        assertNull(index.byNpcUuid(npc));
        assertEquals(LocationKind.LIVE, index.get(live.profileId()).location().kind());
        assertEquals(1, index.get(live.profileId()).generation());
        assertEquals("body", loaded.get(live.profileId()));
        assertTrue(events.isEmpty(), "no snapshot queued and no flush");
    }

    @Test
    void aCaptureThatClearsTheOwnerFilesTheRecordUnowned() {
        CompanionRecord live = insertLive(0);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null))
                .capture(stamped(live, null)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertTrue(index.fileRecords(null).stream().anyMatch(r -> r.profileId().equals(live.profileId())));
        assertTrue(index.fileRecords(owner).isEmpty());
        assertEquals(List.of("snapshot", "flush", "flush"), events, "the unowned file and the old owner's file");
    }

    private static final String BARN = "runeteria:husbandry_owned";

    private static PopulationAdmissionProviderDecision allowBarn(int limit) {
        return new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.ALLOW, "ok",
                Set.of(new PopulationDomainClaim(BARN, 1, true, false)), Map.of(BARN, limit), 1L, 0L);
    }

    /** A flow whose every role is managed by a provider that answers with {@code decision}. */
    private CaptureFlow<String> managedFlow(CompletableFuture<PopulationAdmissionProviderDecision> decision) {
        ProviderAdmission providers = new ProviderAdmission(
                role -> new ProviderAdmission.Managed("husbandry", 1, "husbandry", "sheep", Set.of("sheep"),
                        "husbandry.sheep", 1, 0L),
                request -> { events.add("provider"); return decision; });
        CompanionAdmissionGate.Check domainLimits = (before, after, provided) -> {
            CompanionAdmission.DomainRefusal refused = CompanionAdmission.checkDomains(
                    index.fileRecords(after.ownerUuid()), before, after, provided);
            return refused == null ? null
                    : new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED, refused.messageKey());
        };
        return new CaptureFlow<>(index, loaded, (id, envelope) -> events.add("snapshot"),
                owner -> { events.add("flush"); return CompletableFuture.completedFuture(null); },
                providers, domainLimits);
    }

    private CaptureFlow.Capture<String> wild(UUID npc) {
        return new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);
    }

    @Test
    void anAllowedCaptureWaitsForTheProviderAndStoresItsClaimsOnTheNewRecord() {
        UUID npc = UUID.randomUUID();
        CompletableFuture<PopulationAdmissionProviderDecision> decision = new CompletableFuture<>();

        CompletableFuture<CaptureFlow.Outcome> pending = managedFlow(decision).capture(wild(npc));

        assertEquals(List.of("provider"), events, "nothing is committed before the provider answers");
        assertNull(index.byNpcUuid(npc));

        decision.complete(allowBarn(1));

        assertEquals(CaptureFlow.Result.CAPTURED, pending.join().result());
        CompanionRecord created = index.get(pending.join().itemRef().profileId());
        assertEquals(LocationKind.ITEM, created.location().kind());
        assertEquals(List.of(new DomainClaim(BARN, 1, true, false)), created.domainClaims());
    }

    @Test
    void aCaptureOfTheOwnersOwnCompanionDoesNotAskTheProvider() {
        CompanionRecord live = insertLive(2);

        CaptureFlow.Outcome outcome = managedFlow(new CompletableFuture<>()).capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals(List.of("snapshot", "flush"), events);
    }

    @Test
    void aDeniedOrUnavailableProviderChangesNothingAndCarriesItsMessageKey() {
        UUID npc = UUID.randomUUID();
        CompanionRecord live = insertLive(1);

        CaptureFlow.Outcome denied = managedFlow(CompletableFuture.completedFuture(
                new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.DENY,
                        "runeteria.husbandry.levelTooLow", Set.of(), Map.of(), 1L, 0L))).capture(wild(npc)).join();
        CaptureFlow.Outcome unavailable = managedFlow(CompletableFuture.completedFuture(
                PopulationAdmissionProviderDecision.unavailable("provider-timeout")))
                .capture(stamped(live, UUID.randomUUID())).join();

        assertEquals(new CaptureFlow.Outcome(CaptureFlow.Result.PROVIDER_DENIED, null,
                "runeteria.husbandry.levelTooLow"), denied);
        assertEquals(new CaptureFlow.Outcome(CaptureFlow.Result.PROVIDER_UNAVAILABLE, null,
                CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY), unavailable);
        assertNull(index.byNpcUuid(npc));
        assertEquals(live, index.get(live.profileId()));
        assertEquals("body", loaded.get(live.profileId()));
        assertEquals(List.of("provider", "provider"), events, "no snapshot queued and no flush");
    }

    @Test
    void aDomainLimitFilledWhileTheProviderWasAskedRefusesUnderTheLock() {
        UUID npc = UUID.randomUUID();
        CompletableFuture<PopulationAdmissionProviderDecision> decision = new CompletableFuture<>();
        CompletableFuture<CaptureFlow.Outcome> pending = managedFlow(decision).capture(wild(npc));
        // Another of the owner's companions takes the last barn slot before the provider answers.
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Tamed_Sheep", CompanionLocation.item())
                .ownerUuid(owner).domainClaims(List.of(new DomainClaim(BARN, 1, true, false))).build());

        decision.complete(allowBarn(1));

        assertEquals(new CaptureFlow.Outcome(CaptureFlow.Result.PROVIDER_DENIED, null,
                CompanionAdmission.OWNED_LIMIT_MESSAGE_KEY), pending.join());
        assertNull(index.byNpcUuid(npc));
        assertEquals(List.of("provider"), events, "no snapshot queued and no flush");
    }

    @Test
    void anOwnerChangeWhileTheProviderWasAskedMakesTheCaptureAConflict() {
        CompanionRecord live = insertLive(1);
        CompletableFuture<PopulationAdmissionProviderDecision> decision = new CompletableFuture<>();
        CompletableFuture<CaptureFlow.Outcome> pending = managedFlow(decision).capture(stamped(live, UUID.randomUUID()));
        UUID third = UUID.randomUUID();
        index.update(live.profileId(), live.revision(), CompanionTransitions.ownerChanged(third, "Cy"));

        decision.complete(allowBarn(5));

        assertEquals(CaptureFlow.Result.CONFLICT, pending.join().result());
        assertEquals(third, index.get(live.profileId()).ownerUuid());
        assertEquals(LocationKind.LIVE, index.get(live.profileId()).location().kind());
    }

    @Test
    void aRoleChangeWhileTheProviderWasAskedMakesTheCaptureAConflict() {
        CompanionRecord live = insertLive(1);
        CompletableFuture<PopulationAdmissionProviderDecision> decision = new CompletableFuture<>();
        CompletableFuture<CaptureFlow.Outcome> pending = managedFlow(decision).capture(stamped(live, UUID.randomUUID()));
        // The body grew up: the claims the provider allows were computed for the old role.
        index.update(live.profileId(), live.revision(), b -> b.roleId("Tamed_Ram"));

        decision.complete(allowBarn(5));

        assertEquals(CaptureFlow.Result.CONFLICT, pending.join().result());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(List.of(), after.domainClaims());
        assertEquals("body", loaded.get(live.profileId()));
    }

    private static final String ROSTER = "hydragon:horn";
    private static final String TAMED_DRAKE = "Tamed_NordicDrake";

    /** One family of {@link #ROSTER} that allows {@link #TAMED_DRAKE}. */
    private static BondedRecords.Families drakes(int maximumOwned) {
        BondedCompanionPolicy family = new BondedCompanionPolicy(1L, ROSTER, "hydragon:full_dragons",
                Set.of(TAMED_DRAKE), maximumOwned, 0, 0L, 0L, 0L, null, null, null,
                new BondedCompanionPolicy.FeatureFlags(true, true, true, true, true));
        return (rosterId, roleId) -> ROSTER.equals(rosterId) && TAMED_DRAKE.equals(roleId) ? family : null;
    }

    /** A wild body captured into the capturer's bonded roster as the tamed role. */
    private CaptureFlow.Capture<String> wildIntoRoster(UUID npc) {
        CompanionTransitions.BodyFacts wild = new CompanionTransitions.BodyFacts(npc, null, null, "NordicDrake",
                null, "default", 0, 0, 0, List.of(), CompanionSummary.EMPTY);
        return new CaptureFlow.Capture<>(null, 0, "wild", wild, owner, "Alec", DATA,
                new CaptureFlow.BondedTarget(ROSTER, TAMED_DRAKE, "{\"attempt\":7}", 90_000L, null));
    }

    private CaptureFlow<String> rosterFlow(BondedRecords.Families families) {
        return new CaptureFlow<>(index, loaded, (id, envelope) -> { events.add("snapshot"); snapshots.add(envelope); },
                who -> { events.add("flush"); return CompletableFuture.completedFuture(null); },
                ProviderAdmission.none(),
                BondedAdmission.withFamilyCaps((before, after, provided) -> null, index::fileRecords, families));
    }

    @Test
    void aCaptureIntoABondedRosterStoresTheCompanionWithItsEvidenceAndNoItemLocation() {
        UUID npc = UUID.randomUUID();

        CaptureFlow.Outcome outcome = rosterFlow(drakes(0)).capture(wildIntoRoster(npc)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        CompanionRecord stored = index.get(outcome.itemRef().profileId());
        assertEquals(LocationKind.STORED, stored.location().kind());
        assertEquals(StoredReason.BONDED, stored.location().reason());
        assertTrue(stored.bonded());
        assertEquals(ROSTER, stored.rosterId());
        assertEquals(TAMED_DRAKE, stored.roleId(), "the record has the role its roster family allows");
        assertEquals(owner, stored.ownerUuid());
        assertEquals(new ExtensionEntry(1L, "{\"attempt\":7}"),
                stored.extensions().get(BondedRecords.CAPTURE_EVIDENCE_KEY));
        assertEquals(90_000L, stored.summonCooldownUntilMs(),
                "not summonable before the family cooldown, so not while its body is being removed");
        assertEquals(List.of("snapshot", "flush"), events);
        assertEquals(stored.generation(), snapshots.get(0).generation());
    }

    @Test
    void aCompanionOfTheOwnerCapturedIntoARosterLeavesItsBodyRegistrationAndLinks() {
        CompanionRecord live = insertLive(2);
        index.update(live.profileId(), live.revision(), b -> b.toolIds(List.of("link")));
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(live.profileId(), 2, "body",
                facts(live.currentNpcUuid()), owner, "Alec", DATA,
                new CaptureFlow.BondedTarget(ROSTER, TAMED_DRAKE, "{}", 0L, null));

        CaptureFlow.Outcome outcome = rosterFlow(drakes(0)).capture(capture).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        CompanionRecord stored = index.get(live.profileId());
        assertEquals(StoredReason.BONDED, stored.location().reason());
        assertEquals(3, stored.generation());
        assertTrue(stored.bonded());
        assertEquals(List.of(), stored.toolIds());
        assertNull(loaded.get(live.profileId()));
    }

    @Test
    void aFullFamilyRefusesACaptureIntoItsRosterUnderTheLockAndNothingChanges() {
        index.insert(CompanionRecord.builder(UUID.randomUUID(), TAMED_DRAKE, CompanionLocation.stored(StoredReason.BONDED))
                .ownerUuid(owner).bonded(true).rosterId(ROSTER).build());
        UUID npc = UUID.randomUUID();

        CaptureFlow.Outcome outcome = rosterFlow(drakes(1)).capture(wildIntoRoster(npc)).join();

        assertEquals(CaptureFlow.Result.OWNED_LIMIT, outcome.result());
        assertEquals(CompanionAdmission.OWNED_LIMIT_MESSAGE_KEY, outcome.messageKey());
        assertNull(index.byNpcUuid(npc));
        assertEquals(1, index.fileRecords(owner).size());
        assertTrue(events.isEmpty(), "no snapshot queued and no flush");
    }

    @Test
    void aBondedCaptureThatIsNotWrittenLeavesNoCompanionInTheRoster() {
        UUID npc = UUID.randomUUID();
        CaptureFlow<String> failing = new CaptureFlow<>(index, loaded, (id, envelope) -> { },
                who -> CompletableFuture.failedFuture(new IllegalStateException("disk full")),
                ProviderAdmission.none(), (before, after, provided) -> null);

        CaptureFlow.Outcome outcome = failing.capture(wildIntoRoster(npc)).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        assertFalse(index.fileRecords(owner).stream().anyMatch(CompanionRecord::countsAsOwned));
    }

    @Test
    void anUndoneBondedCaptureOfALiveCompanionPutsBackTheSnapshotOfTheRoleItStillHas() {
        CompanionRecord live = insertLive(2);
        BsonDocument asTaken = new BsonDocument("Entity", new BsonString("old role"));
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(live.profileId(), 2, "body",
                facts(live.currentNpcUuid()), owner, "Alec", DATA,
                new CaptureFlow.BondedTarget(ROSTER, TAMED_DRAKE, "{}", 0L, asTaken));

        CaptureFlow.Outcome outcome = flow(CompletableFuture.failedFuture(new IllegalStateException("disk full")))
                .capture(capture).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        assertEquals(live.roleId(), index.get(live.profileId()).roleId());
        assertEquals("body", loaded.get(live.profileId()));
        SnapshotEnvelope requeued = snapshots.get(snapshots.size() - 1);
        assertEquals(2, requeued.generation());
        assertEquals(asTaken, requeued.data(), "the body lives on in its old role");
    }

    @Test
    void aBondedCaptureOfAManagedRoleAsksTheProviderAndStoresItsClaims() {
        UUID npc = UUID.randomUUID();

        CaptureFlow.Outcome outcome = managedFlow(CompletableFuture.completedFuture(allowBarn(1)))
                .capture(wildIntoRoster(npc)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        CompanionRecord stored = index.get(outcome.itemRef().profileId());
        assertEquals(StoredReason.BONDED, stored.location().reason());
        assertEquals(List.of(new DomainClaim(BARN, 1, true, false)), stored.domainClaims());
        assertEquals(List.of("provider", "snapshot", "flush"), events);
    }
}
