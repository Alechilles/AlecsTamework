package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionData;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Regression coverage for the non-blocking, generation-fenced panel boundary. */
class BondedCompanionPanelSnapshotCacheTest {
    private static final UUID OWNER = BondedPanelTestFixtures.OWNER;
    private static final String ROSTER = "hydragon:dragons";
    private static final BondedCompanionPanelSnapshotCache.Settings SETTINGS =
            new BondedCompanionPanelSnapshotCache.Settings(
                    2, 100L, 1_000L, 10L, 40L);

    @Test
    void firstPeekReturnsBeforeApiInvocationAndCoalescesOneWorkerLoad() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        AtomicInteger suppliers = new AtomicInteger();
        var cache = cache(() -> {
            suppliers.incrementAndGet();
            return api;
        }, worker, now);

        var first = cache.peek(OWNER, ROSTER);
        var second = cache.peek(OWNER, ROSTER);

        assertEquals(0, suppliers.get());
        assertEquals(0, api.listCalls.get());
        assertEquals(1, worker.queued());
        assertEquals(BondedCompanionPanelSnapshotCache.State.REFRESHING,
                first.state());
        assertEquals(first.generation(), second.generation());

        worker.runNext();

        assertEquals(1, suppliers.get());
        assertEquals(1, api.listCalls.get());
        assertTrue(api.invokedOnlyInsideWorker);
    }

    @Test
    void invalidationDiscardsStaleCompletionAndPublishesOneCoherentGeneration() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);
        BondedCompanionProfileView old = profile("old", 1L);
        BondedCompanionProfileView current = profile("current", 2L);

        cache.peek(OWNER, ROSTER);
        worker.runNext();
        api.fire(new BondedCompanionChangedEvent(
                "current", OWNER, ROSTER, BondedCompanionStateView.STORED,
                BondedCompanionStateView.ACTIVE, 2L, "summoned"));
        api.completeNext(List.of(old));

        assertEquals(1, worker.queued(),
                "invalidated in-flight work should enqueue exactly one successor");
        assertTrue(cache.peek(OWNER, ROSTER).profiles().isEmpty(),
                "the invalidated generation must never publish");

        worker.runNext();
        api.completeNext(List.of(current));
        var ready = cache.peek(OWNER, ROSTER);

        assertEquals(BondedCompanionPanelSnapshotCache.State.READY,
                ready.state());
        assertEquals(List.of("current"), ready.profiles().stream()
                .map(BondedCompanionProfileView::profileId).toList());
        assertNotEquals(1L, ready.generation());
    }

    @Test
    void eventThatInvalidatesQueuedRefreshStillStartsItsSuccessor() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);

        load(cache, api, worker, OWNER, profile("initial", 1L));
        now.set(101L);
        cache.peek(OWNER, ROSTER);
        api.fire(change(OWNER, "current", 2L));

        worker.runNext();
        assertEquals(1, worker.queued(),
                "a stale queued load must release the single-flight slot");
        worker.runNext();
        api.completeNext(List.of(profile("current", 2L)));

        assertEquals(List.of("current"), cache.peek(OWNER, ROSTER)
                .profiles().stream().map(BondedCompanionProfileView::profileId)
                .toList());
    }

    @Test
    void passiveRefreshKeepsPublishedCardsTrustedWhileReloadingOrRetrying() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);
        BondedCompanionProfileView profile = profile("kept", 7L);

        cache.peek(OWNER, ROSTER);
        worker.runNext();
        api.completeNext(List.of(profile));
        now.set(101L);
        var refreshing = cache.peek(OWNER, ROSTER);
        worker.runNext();
        api.failNext();
        var failed = cache.peek(OWNER, ROSTER);

        assertEquals(List.of(profile), refreshing.profiles());
        assertTrue(refreshing.trusted(),
                "a routine background refresh must not disable a usable card");
        assertEquals(BondedCompanionPanelSnapshotCache.State.REFRESHING,
                refreshing.state());
        assertEquals(List.of(profile), failed.profiles());
        assertTrue(failed.trusted(),
                "a failed background refresh must retain the last safe card");
        assertEquals(BondedCompanionPanelSnapshotCache.State.FAILED,
                failed.state());
    }

    @Test
    void retryDelayDoublesOnlyToTheConfiguredCap() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);

        failOneLoad(cache, api, worker);
        assertNoRetryUntil(cache, worker, now, 10L);
        failQueuedLoad(api, worker);
        assertNoRetryUntil(cache, worker, now, 20L);
        failQueuedLoad(api, worker);
        assertNoRetryUntil(cache, worker, now, 40L);
        failQueuedLoad(api, worker);
        assertNoRetryUntil(cache, worker, now, 40L);
    }

    @Test
    void committedEventsSeedEvictedRostersForTheNextNormalPanelOpen() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);
        UUID ownerTwo = UUID.fromString(
                "71000000-0000-0000-0000-000000000002");
        UUID ownerThree = UUID.fromString(
                "71000000-0000-0000-0000-000000000003");

        load(cache, api, worker, OWNER, profile("one", 1L));
        load(cache, api, worker, ownerTwo, profile(ownerTwo, "two", 1L));
        load(cache, api, worker, ownerThree, profile(ownerThree, "three", 1L));
        int queued = worker.queued();
        api.fire(change(OWNER, "one", 2L));
        assertEquals(queued + 1, worker.queued(),
                "a committed change must seed an evicted roster before its first page");

    }

    @Test
    void closeUnsubscribesAndIgnoresLateCallbacks() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);

        cache.peek(OWNER, ROSTER);
        worker.runNext();
        cache.close();
        api.completeNext(List.of(profile("late", 9L)));
        api.fire(change(OWNER, "late", 10L));
        var closed = cache.peek(OWNER, ROSTER);

        assertEquals(1, api.closedSubscriptions.get());
        assertEquals(BondedCompanionPanelSnapshotCache.State.CLOSED,
                closed.state());
        assertTrue(closed.profiles().isEmpty());
        assertEquals(0, worker.queued());
    }

    /** Disconnect must discard presentation and fence an already pending load. */
    @Test
    void ownerEvictionPreventsLateLoadFromRepublishingPresentation() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);

        cache.peek(OWNER, ROSTER);
        worker.runNext();
        cache.evictOwner(OWNER);
        api.completeNext(List.of(profile("late", 1L)));

        var afterDisconnect = cache.peek(OWNER, ROSTER);
        assertTrue(afterDisconnect.profiles().isEmpty());
        assertEquals(BondedCompanionPanelSnapshotCache.State.REFRESHING,
                afterDisconnect.state());
    }

    /** Rebinding the API invalidates every opened roster and loads fresh cards. */
    @Test
    void apiReplacementInvalidatesThenPublishesAFreshGeneration() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi first = new ControlledApi(worker);
        ControlledApi replacement = new ControlledApi(worker);
        java.util.concurrent.atomic.AtomicReference<BondedCompanionApi> bound =
                new java.util.concurrent.atomic.AtomicReference<>(first);
        var cache = cache(bound::get, worker, now);

        load(cache, first, worker, OWNER, profile("old", 1L));
        long oldGeneration = cache.peek(OWNER, ROSTER).generation();
        bound.set(replacement);
        cache.refreshBoundApi();
        worker.runNext();
        replacement.completeNext(List.of(profile("new", 2L)));

        var refreshed = cache.peek(OWNER, ROSTER);
        assertTrue(refreshed.generation() > oldGeneration);
        assertEquals(List.of("new"), refreshed.profiles().stream()
                .map(BondedCompanionProfileView::profileId).toList());
    }

    @Test
    void subscriberIsNotifiedForPublicationAndReplacementThenCanUnsubscribe()
            throws Exception {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);
        AtomicInteger notifications = new AtomicInteger();
        AutoCloseable subscription = cache.subscribe(OWNER, ROSTER,
                notifications::incrementAndGet);

        cache.peek(OWNER, ROSTER);
        worker.runNext();
        api.completeNext(List.of(profile("ready", 1L)));

        assertEquals(1, notifications.get());

        api.fire(change(OWNER, "ready", 2L));

        assertEquals(2, notifications.get());
        worker.runNext();
        api.completeNext(List.of(profile("replacement", 2L)));

        assertEquals(3, notifications.get());
        subscription.close();
        api.fire(change(OWNER, "replacement", 3L));

        assertEquals(3, notifications.get());
    }

    @Test
    void subscriberIsNotifiedWhenLoadFails() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong now = new AtomicLong();
        ControlledApi api = new ControlledApi(worker);
        var cache = cache(() -> api, worker, now);
        AtomicInteger notifications = new AtomicInteger();
        cache.subscribe(OWNER, ROSTER, notifications::incrementAndGet);

        cache.peek(OWNER, ROSTER);
        worker.runNext();
        api.failNext();

        assertEquals(1, notifications.get());
    }

    private void failOneLoad(BondedCompanionPanelSnapshotCache cache,
                             ControlledApi api, ManualExecutor worker) {
        cache.peek(OWNER, ROSTER);
        failQueuedLoad(api, worker);
    }

    private void failQueuedLoad(ControlledApi api, ManualExecutor worker) {
        worker.runNext();
        api.failNext();
    }

    private void assertNoRetryUntil(BondedCompanionPanelSnapshotCache cache,
                                    ManualExecutor worker, AtomicLong now,
                                    long delay) {
        long start = now.get();
        now.set(start + delay - 1L);
        cache.peek(OWNER, ROSTER);
        assertEquals(0, worker.queued());
        now.set(start + delay);
        cache.peek(OWNER, ROSTER);
        assertEquals(1, worker.queued());
    }

    private void load(BondedCompanionPanelSnapshotCache cache,
                      ControlledApi api, ManualExecutor worker, UUID owner,
                      BondedCompanionProfileView profile) {
        cache.peek(owner, ROSTER);
        worker.runNext();
        api.completeNext(List.of(profile));
    }

    private BondedCompanionPanelSnapshotCache cache(
            java.util.function.Supplier<BondedCompanionApi> api,
            ManualExecutor worker, AtomicLong now) {
        return new BondedCompanionPanelSnapshotCache(
                api, worker, now::get, SETTINGS);
    }

    private BondedCompanionChangedEvent change(
            UUID owner, String profile, long revision) {
        return new BondedCompanionChangedEvent(
                profile, owner, ROSTER, BondedCompanionStateView.STORED,
                BondedCompanionStateView.ACTIVE, revision, "summoned");
    }

    private BondedCompanionProfileView profile(String id, long revision) {
        return profile(OWNER, id, revision);
    }

    private BondedCompanionProfileView profile(
            UUID owner, String id, long revision) {
        BondedCompanionProfileView source = BondedPanelTestFixtures.profile(
                id, revision, BondedCompanionStateView.STORED, null,
                java.util.Map.of());
        return new BondedCompanionProfileView(
                source.profileId(), owner, source.rosterId(), source.familyId(),
                source.roleId(), source.displayName(), source.species(),
                source.gender(), source.revision(), source.state(),
                source.summonAvailable(), source.storeAvailable(),
                source.reviveAvailable(), source.snapshotPresentationData(),
                source.activeLease(), source.summonCooldownUntilMs(),
                source.reviveQuote());
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        int queued() {
            return tasks.size();
        }

        boolean running() {
            return running;
        }

        void runNext() {
            Runnable task = tasks.removeFirst();
            running = true;
            try {
                task.run();
            } finally {
                running = false;
            }
        }
    }

    private static final class ControlledApi implements BondedCompanionApi {
        private final ManualExecutor worker;
        private final ArrayDeque<CompletableFuture<BondedCompanionResult<
                List<BondedCompanionProfileView>>>> pending = new ArrayDeque<>();
        private Consumer<BondedCompanionChangedEvent> listener;
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger closedSubscriptions = new AtomicInteger();
        private boolean invokedOnlyInsideWorker = true;

        private ControlledApi(ManualExecutor worker) {
            this.worker = worker;
        }

        @Override
        public BondedCompanionAvailability availability() {
            invokedOnlyInsideWorker &= worker.running();
            return BondedCompanionAvailability.availableNow();
        }

        @Override
        public CompletableFuture<BondedCompanionResult<
                List<BondedCompanionProfileView>>> list(
                UUID ownerUuid, String rosterId) {
            invokedOnlyInsideWorker &= worker.running();
            listCalls.incrementAndGet();
            CompletableFuture<BondedCompanionResult<
                    List<BondedCompanionProfileView>>> future =
                    new CompletableFuture<>();
            pending.addLast(future);
            return future;
        }

        @Override
        public AutoCloseable subscribe(
                Consumer<BondedCompanionChangedEvent> listener) {
            invokedOnlyInsideWorker &= worker.running();
            this.listener = listener;
            return closedSubscriptions::incrementAndGet;
        }

        void fire(BondedCompanionChangedEvent event) {
            if (listener != null) listener.accept(event);
        }

        void completeNext(List<BondedCompanionProfileView> profiles) {
            pending.removeFirst().complete(new BondedCompanionResult<>(
                    BondedCompanionResultCode.SUCCESS, profiles, null));
        }

        void failNext() {
            pending.removeFirst().completeExceptionally(
                    new IllegalStateException("sqlite test failure"));
        }

        @Override public CompletableFuture<BondedCompanionResult<
                BondedCompanionProfileView>> provision(
                BondedCompanionProvisionRequest request) { throw unused(); }
        @Override public CompletableFuture<BondedCompanionResult<
                BondedCompanionProfileView>> summon(
                BondedCompanionActionRequest request) { throw unused(); }
        @Override public CompletableFuture<BondedCompanionResult<
                BondedCompanionProfileView>> store(
                BondedCompanionActionRequest request) { throw unused(); }
        @Override public CompletableFuture<BondedCompanionResult<
                BondedCompanionReviveQuote>> quoteRevive(
                BondedCompanionActionRequest request) { throw unused(); }
        @Override public CompletableFuture<BondedCompanionResult<
                BondedCompanionProfileView>> revive(
                BondedCompanionReviveRequest request) { throw unused(); }
        @Override public CompletableFuture<BondedCompanionResult<
                BondedCompanionExtensionData>> getExtensionData(
                BondedCompanionExtensionDataKey key) { throw unused(); }
        @Override public CompletableFuture<BondedCompanionResult<
                BondedCompanionExtensionData>> compareAndSetExtensionData(
                BondedCompanionExtensionDataUpdate update) { throw unused(); }

        private UnsupportedOperationException unused() {
            return new UnsupportedOperationException("unused test method");
        }
    }
}
