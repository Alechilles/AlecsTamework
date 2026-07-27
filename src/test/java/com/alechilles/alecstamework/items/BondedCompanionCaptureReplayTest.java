package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionCapturePersistenceAdapter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionSchemaManager;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for durable capture replay before mutable policy. */
class BondedCompanionCaptureReplayTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString(
            "90000000-0000-0000-0000-000000000009");
    @TempDir Path tempDir;

    /** Regression: removed policy cannot preempt an exact gameplay replay. */
    @Test
    void routedReplayBypassesRemovedFamilyAndRetriesOnlyCleanup()
            throws Exception {
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        Fixture fixture = fixture("removed-family-routed-replay.sqlite", rosters);
        ReplayHarness harness = new ReplayHarness(fixture.persistence);
        var original = frozenIntentAt(10L);

        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED,
                harness.author.capture(original).status());
        assertTrue(rosters.replace(List.of(), 5L).applied());

        var replay = resume(harness, original);

        assertReplayOnly(replay, harness);
    }

    /** A new channel attempt is only a trigger for the committed operation. */
    @Test
    void freshInteractionAttemptResumesPriorDurableCleanup() throws Exception {
        Fixture fixture = fixture("fresh-attempt-routed-replay.sqlite",
                rosterRegistry());
        ReplayHarness harness = new ReplayHarness(fixture.persistence);
        var original = frozenIntentAt(10L);

        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED,
                harness.author.capture(original).status());
        CaptureAttemptHandle trigger = freshTriggerAttempt();
        assertNotEquals(original.attemptEvidence().attemptId(),
                trigger.attemptId());

        assertReplayOnly(resume(harness, original, trigger), harness);
    }

    /** Regression: newly ambiguous policy cannot preempt exact replay. */
    @Test
    void routedReplayBypassesNewFamilyAmbiguityWithoutRerolling()
            throws Exception {
        BondedCompanionRosterRegistry rosters = registry(
                rosterPolicy("Dragons", "hydragon:dragon", "Shared_Role"));
        Fixture fixture = fixture("ambiguous-family-routed-replay.sqlite", rosters);
        ReplayHarness harness = new ReplayHarness(fixture.persistence);
        var original = familyIntent("routed-ambiguous", "Shared_Role");

        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED,
                harness.author.capture(original).status());
        assertTrue(rosters.replace(List.of(
                rosterPolicy("Dragons", "hydragon:dragon", "Shared_Role"),
                rosterPolicy("Minis", "hydragon:miniwyvern", "Shared_Role")
        ), 5L).applied());

        var replay = resume(harness, original);

        assertReplayOnly(replay, harness);
    }

    /** Exact replay must use the committed snapshot, not changed live fields. */
    @Test
    void routedReplaySurvivesLiveSnapshotMutationAfterCommit()
            throws Exception {
        Fixture fixture = fixture("changed-live-routed-replay.sqlite",
                rosterRegistry());
        ReplayHarness harness = new ReplayHarness(fixture.persistence);
        var original = frozenIntentAt(10L);

        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED,
                harness.author.capture(original).status());
        var changedLive = withSnapshot(
                original, snapshot(SOURCE, "Dragon_Fire", 99L, 0.15D));

        var replay = resume(harness, changedLive);

        assertReplayOnly(replay, harness);
    }

    @Test
    void absentReplayLeavesFreshCaptureOnNormalPolicyPath() throws Exception {
        Fixture fixture = fixture("absent-routed-replay.sqlite",
                rosterRegistry());
        ReplayHarness harness = new ReplayHarness(fixture.persistence);
        var fresh = frozenIntentAt(10L);
        AtomicInteger replayFreezes = new AtomicInteger();

        var replay = harness.route.resume(request(fresh), evidence -> {
            replayFreezes.incrementAndGet();
            return harness.replayIntents.intent(
                    request(fresh), freshTriggerAttempt(), evidence);
        }, null);

        assertFalse(replay.handled());
        assertEquals(0, replayFreezes.get());
        assertEquals(0, harness.policyChecks.get());
        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED,
                harness.author.capture(fresh).status());
        assertEquals(1, harness.policyChecks.get());
    }

    @Test
    void changedStableIdentityCannotAdoptPriorDurableCapture() throws Exception {
        Fixture fixture = fixture("mismatched-replay-identity.sqlite",
                rosterRegistry());
        ReplayHarness harness = new ReplayHarness(fixture.persistence);
        var original = frozenIntentAt(10L);

        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED,
                harness.author.capture(original).status());
        var same = request(original);
        var otherActor = withIdentity(
                same, OTHER, same.rosterId(), same.sourceNpcUuid(),
                same.sourceItemId(), same.roleId());
        var otherRoster = withIdentity(
                same, same.actorUuid(), "other:roster", same.sourceNpcUuid(),
                same.sourceItemId(), same.roleId());

        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.CONFLICT,
                harness.author.lookupReplay(otherActor).status());
        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.CONFLICT,
                harness.author.lookupReplay(otherRoster).status());
        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.ABSENT,
                harness.author.lookupReplay(withIdentity(
                        same, same.actorUuid(), same.rosterId(), OTHER,
                        same.sourceItemId(), same.roleId())).status());
        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.CONFLICT,
                harness.author.lookupReplay(withIdentity(
                        same, same.actorUuid(), same.rosterId(),
                        same.sourceNpcUuid(), "Other_Stone",
                        same.roleId())).status());
        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.CONFLICT,
                harness.author.lookupReplay(withIdentity(
                        same, same.actorUuid(), same.rosterId(),
                        same.sourceNpcUuid(), same.sourceItemId(),
                        "Other_Role")).status());
        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.CONFLICT,
                harness.author.lookupReplay(withWorld(
                        same, "other-world")).status());

        assertConflictHandled(harness, otherActor);
        assertConflictHandled(harness, otherRoster);
        assertEquals(1, fixture.database.listProfiles(
                OWNER, "hydragon:companions").size());
        assertTrue(fixture.database.listProfiles(
                OTHER, "hydragon:companions").isEmpty());
        assertTrue(fixture.database.listProfiles(
                OWNER, "other:roster").isEmpty());

        assertEquals(1, harness.policyChecks.get());
        assertEquals(1, harness.cleanupAttempts.get());
        assertEquals(1, harness.effects.get());
        assertEquals(1, harness.spends.get());
    }

    private void assertConflictHandled(
            ReplayHarness harness,
            BondedCompanionCaptureReplayGateway.Request request
    ) {
        var result = harness.route.resume(
                request,
                evidence -> { throw new AssertionError("must not freeze"); },
                null);
        assertTrue(result.handled());
        assertEquals(BondedCompanionCaptureAuthor.Status.DATABASE_FAILED,
                result.result().status());
    }

    /** Architecture fence for the actual Hytale capture callback ordering. */
    @Test
    void captureRouteInvokesReplayBeforeAllCurrentAdmissionGates()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "BondedCompanionCaptureRoute.java"));

        int replay = source.indexOf("replays.resume(");
        int handled = source.indexOf("if (resumed.handled()) return true;");
        int sourceEligibility = source.indexOf("if (!sourceEligible)");
        int admission = source.indexOf("admission.assess(");
        int roll = source.indexOf("resolveAdmission(");

        assertTrue(replay >= 0 && handled > replay
                        && sourceEligibility > handled
                        && admission > sourceEligibility && roll > admission,
                "exact replay must precede source, family, and chance gates; "
                        + "ABSENT evidence must fall through to all gates");
    }

    private void assertReplayOnly(
            BondedCompanionCaptureReplayRoute.Result replay,
            ReplayHarness harness
    ) {
        assertTrue(replay.handled());
        assertEquals(BondedCompanionCaptureAuthor.Status.REPLAYED,
                replay.result().status());
        assertEquals(BondedCompanionCaptureAuthor.CleanupOutcome.RETRY_PENDING,
                replay.result().cleanupOutcome());
        assertEquals(1, harness.policyChecks.get());
        assertEquals(2, harness.cleanupAttempts.get());
        assertEquals(1, harness.effects.get());
        assertEquals(1, harness.spends.get());
    }

    private Fixture fixture(String name, BondedCompanionRosterRegistry rosters) {
        Path path = tempDir.resolve(name);
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        var database = new SqliteBondedCompanionDatabase(path);
        var persistence = new SqliteBondedCompanionCapturePersistenceAdapter(
                rosters,
                new BondedCompanionTransitionService(
                        new BondedCompanionPolicyResolver(rosters)),
                database, database,
                new SqliteBondedCompanionProjectionDurability(path),
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.RETRY_REQUIRED)
        );
        return new Fixture(path, database, persistence);
    }

    private static BondedCompanionCaptureReplayGateway.Request request(
            BondedCompanionCaptureIntent intent
    ) {
        return new BondedCompanionCaptureReplayGateway.Request(
                intent.callerNamespace(), intent.idempotencyKey(),
                intent.actorUuid(), intent.rosterId(), intent.sourceNpcUuid(),
                intent.worldKey(), intent.attemptEvidence().sourceItemId(),
                intent.roleId());
    }

    private static BondedCompanionCaptureReplayGateway.Request withIdentity(
            BondedCompanionCaptureReplayGateway.Request source,
            UUID actorUuid,
            String rosterId,
            UUID sourceNpcUuid,
            String sourceItemId,
            String roleId
    ) {
        return new BondedCompanionCaptureReplayGateway.Request(
                source.callerNamespace(),
                actorUuid + ":" + rosterId + ":" + sourceNpcUuid, actorUuid,
                rosterId, sourceNpcUuid, source.sourceWorldKey(), sourceItemId,
                roleId);
    }

    private static BondedCompanionCaptureReplayGateway.Request withWorld(
            BondedCompanionCaptureReplayGateway.Request source,
            String sourceWorldKey
    ) {
        return new BondedCompanionCaptureReplayGateway.Request(
                source.callerNamespace(), source.idempotencyKey(),
                source.actorUuid(), source.rosterId(), source.sourceNpcUuid(),
                sourceWorldKey, source.sourceItemId(), source.roleId());
    }

    private static BondedCompanionCaptureReplayRoute.Result resume(
            ReplayHarness harness,
            BondedCompanionCaptureIntent current
    ) {
        return resume(harness, current, freshTriggerAttempt());
    }

    private static BondedCompanionCaptureReplayRoute.Result resume(
            ReplayHarness harness,
            BondedCompanionCaptureIntent current,
            CaptureAttemptHandle trigger
    ) {
        var request = request(current);
        return harness.route.resume(
                request,
                evidence -> harness.replayIntents.intent(
                        request, trigger, evidence),
                null);
    }

    private static BondedCompanionCaptureIntent frozenIntentAt(long time) {
        return new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1",
                OWNER + ":hydragon:companions:" + SOURCE,
                OWNER, "world", 2, "fingerprint", SOURCE,
                attempt(), "Dragon_Fire", null, "hydragon:companions", 4L,
                snapshot(SOURCE, "Dragon_Fire", time), completionEffect(),
                true, true, true, true, true, true, "hydragon:dragon",
                BondedCompanionCaptureIntent.FamilySelection.ROLE_INFERRED);
    }

    private static BondedCompanionCaptureIntent familyIntent(
            String key, String roleId
    ) {
        return new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1", key, OWNER, "world", 2,
                "fingerprint-" + key, SOURCE, attempt(), roleId, null,
                "hydragon:companions", 4L, snapshot(SOURCE, roleId, 10L),
                completionEffect(),
                true, true, true, true, true, true, null,
                BondedCompanionCaptureIntent.FamilySelection.ROLE_INFERRED);
    }

    private static BondedCompanionCaptureAttemptEvidence attempt() {
        return new BondedCompanionCaptureAttemptEvidence(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                "Ancient_Stone", "HydragonCapture", 7L, null, -1L,
                CaptureSourceConsumption.SUCCESS_ONLY,
                CaptureSuccessDisposition.STORE_BONDED_COMPANION,
                CaptureAttemptOutcome.CAPTURED, "guaranteed");
    }

    private static BondedCompanionSnapshot snapshot(
            UUID source, String role, long time
    ) {
        return snapshot(source, role, time, 0.75D);
    }

    private static BondedCompanionSnapshot snapshot(
            UUID source, String role, long time, double health
    ) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                source, null, -1, role, null, null, null, null, null, null,
                null, null, null, null, null, null, health, time), Map.of());
    }

    private static BondedCompanionCaptureIntent withSnapshot(
            BondedCompanionCaptureIntent source,
            BondedCompanionSnapshot snapshot
    ) {
        return new BondedCompanionCaptureIntent(
                source.callerNamespace(), source.idempotencyKey(),
                source.actorUuid(), source.worldKey(), source.hotbarSlot(),
                source.sourceFingerprint(), source.sourceNpcUuid(),
                source.attemptEvidence(), source.roleId(), source.species(),
                source.rosterId(), source.rosterRevision(), snapshot,
                source.completionEffect(), source.targetValid(),
                source.chanceSuccessful(), source.tranquilized(),
                source.toolAccess(), source.ownerAllowed(), source.roleAllowed(),
                source.familyId(), source.familySelection());
    }

    private static CaptureAttemptHandle freshTriggerAttempt() {
        return new CaptureAttemptHandle(
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                null, null, 3, "changed-live-source-fingerprint");
    }

    private static SpawnerPublishedEffect completionEffect() {
        return new SpawnerPublishedEffect(
                1D, 2D, 3D, "capture-burst", "capture-success");
    }

    private static BondedCompanionRosterRegistry rosterRegistry()
            throws Exception {
        return registry(rosterPolicy(
                "HydragonCompanions", "hydragon:dragon", "Dragon_Fire"));
    }

    private static BondedCompanionRosterRegistry registry(
            TwBondedCompanionRosterConfig... policies
    ) {
        var registry = new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(policies), 4L).applied());
        return registry;
    }

    private static TwBondedCompanionRosterConfig rosterPolicy(
            String id, String familyId, String roleId
    ) throws Exception {
        var config = TwBondedCompanionRosterConfig.CODEC.decode(
                BsonDocument.parse("""
                        {"RosterId":"hydragon:companions","FamilyId":"%s",
                         "AllowedRoles":["%s"],"MaximumOwned":3,
                         "MaximumActive":1,"Features":{"Capture":true}}
                        """.formatted(familyId, roleId)), new ExtraInfo());
        Field configId = config.getClass().getDeclaredField("id");
        configId.setAccessible(true);
        configId.set(config, id);
        return config;
    }

    private record Fixture(
            Path path,
            SqliteBondedCompanionDatabase database,
            SqliteBondedCompanionCapturePersistenceAdapter persistence
    ) {}

    private static final class ReplayHarness {
        private final AtomicInteger policyChecks = new AtomicInteger();
        private final AtomicInteger cleanupAttempts = new AtomicInteger();
        private final AtomicInteger effects = new AtomicInteger();
        private final AtomicInteger spends = new AtomicInteger();
        private final BondedCompanionCaptureAuthor author;
        private final BondedCompanionCaptureReplayRoute route;
        private final BondedCompanionCaptureReplayIntentFactory replayIntents;

        private ReplayHarness(
                SqliteBondedCompanionCapturePersistenceAdapter persistence
        ) {
            author = new BondedCompanionCaptureAuthor(
                    persistence,
                    intent -> {
                        policyChecks.incrementAndGet();
                        return persistence.validate(intent);
                    },
                    persistence::store,
                    intent -> {
                        cleanupAttempts.incrementAndGet();
                        return persistence.cleanup(intent);
                    },
                    new BondedCompanionCaptureFeedbackDispatcher(
                            new ReplaySink(effects, spends)),
                    (intent, failure) -> {});
            route = new BondedCompanionCaptureReplayRoute(author);
            replayIntents = new BondedCompanionCaptureReplayIntentFactory(
                    new SpawnerRolePolicyService(null));
        }
    }

    private record ReplaySink(AtomicInteger effects, AtomicInteger spends)
            implements BondedCompanionCaptureFeedbackDispatcher.Sink {
        @Override public boolean spend(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            spends.incrementAndGet();
            return true;
        }
        @Override public boolean effect(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            effects.incrementAndGet();
            return true;
        }
        @Override public boolean message(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context,
                String message) {
            return true;
        }
    }
}
