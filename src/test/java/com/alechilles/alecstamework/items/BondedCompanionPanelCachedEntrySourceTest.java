package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Cache generations must drive complete cards and actions as one snapshot. */
class BondedCompanionPanelCachedEntrySourceTest {
    private static final UUID OWNER = BondedPanelTestFixtures.OWNER;
    private static final String ROSTER = "hydragon:dragons";

    @Test
    void firstPanelReadIsImmediateThenPublishesOneCardAndFeatureGeneration() {
        QueueExecutor worker = new QueueExecutor();
        MutableApi api = new MutableApi(profile(
                4L, BondedCompanionStateView.STORED));
        BondedCompanionPanelEntrySourceService source = source(api, worker);

        var loading = source.buildSnapshot(OWNER, ROSTER, "world-a");

        assertTrue(loading.entries().isEmpty());
        assertTrue(loading.featurePresentations().isEmpty());
        assertEquals("tamework.ui.linkedPanel.bonded.loading",
                loading.emptyStateKey());
        assertEquals(0, api.listCalls.get());

        worker.runNext();
        var ready = source.buildSnapshot(OWNER, ROSTER, "world-a");

        assertEquals(1, ready.entries().size());
        assertEquals(1, ready.featurePresentations().size());
        var card = ready.entries().getFirst();
        var feature = ready.featurePresentations().get(card.npcUuid()).bonded();
        assertEquals(4L, feature.revision());
        assertFalse(feature.status().actionEnabled(),
                "an ID-only snapshot cannot invent a world placement context");
        assertNull(ready.emptyStateKey());
    }

    /** Cold cache must never be presented as the normal empty linked roster. */
    @Test
    void coldAndUnavailableStatesExposeLocalizedDistinctEmptyStateKeys() {
        QueueExecutor worker = new QueueExecutor();
        BondedCompanionPanelEntrySourceService source = source(
                new MutableApi(profile(4L, BondedCompanionStateView.STORED)),
                worker);

        var loading = source.buildSnapshot(OWNER, ROSTER, "world-a");
        assertEquals("Loading companion information...", LocalizedText.resolve(
                "en-US", loading.emptyStateKey()));
        assertFalse(LocalizedText.resolve("de-DE", loading.emptyStateKey())
                .equals(loading.emptyStateKey()));
    }

    @Test
    void changeKeepsStableCompleteCardDisabledUntilNewGenerationPublishes() {
        QueueExecutor worker = new QueueExecutor();
        MutableApi api = new MutableApi(profile(
                4L, BondedCompanionStateView.STORED));
        BondedCompanionPanelEntrySourceService source = source(api, worker);
        source.buildSnapshot(OWNER, ROSTER, "world-a");
        worker.runNext();
        var initial = source.buildSnapshot(OWNER, ROSTER, "world-a");
        UUID cardId = initial.entries().getFirst().npcUuid();

        api.profile = profile(5L, BondedCompanionStateView.ACTIVE);
        api.fire(new BondedCompanionChangedEvent(
                "profile-cache", OWNER, ROSTER,
                BondedCompanionStateView.STORED,
                BondedCompanionStateView.ACTIVE, 5L, "summoned"));
        var refreshing = source.buildSnapshot(OWNER, ROSTER, "world-a");

        assertEquals(cardId, refreshing.entries().getFirst().npcUuid());
        var stale = refreshing.featurePresentations().get(cardId).bonded();
        assertEquals(4L, stale.revision());
        assertFalse(stale.status().actionEnabled());
        assertEquals(BondedCompanionActionBlockReason.REFRESHING,
                stale.status().blockReason());

        worker.runNext();
        var refreshed = source.buildSnapshot(OWNER, ROSTER, "world-a");
        var current = refreshed.featurePresentations().get(cardId).bonded();
        assertEquals(5L, current.revision());
        assertEquals(BondedCompanionStateView.ACTIVE,
                current.status().state());
        assertTrue(current.status().actionEnabled());
    }

    private BondedCompanionPanelEntrySourceService source(
            MutableApi api, QueueExecutor worker) {
        var cache = new BondedCompanionPanelSnapshotCache(
                () -> api, worker, System::nanoTime,
                new BondedCompanionPanelSnapshotCache.Settings(
                        8, Long.MAX_VALUE, Long.MAX_VALUE, 1L, 8L));
        return new BondedCompanionPanelEntrySourceService(
                cache, new BondedCompanionPanelRecordSource(),
                new BondedCompanionPanelFeaturePresentationSource(() -> 0L));
    }

    private BondedCompanionProfileView profile(
            long revision, BondedCompanionStateView state) {
        return BondedPanelTestFixtures.profile(
                "profile-cache", revision, state,
                state == BondedCompanionStateView.ACTIVE
                        ? UUID.fromString(
                        "71000000-0000-0000-0000-000000000099") : null,
                Map.of("currentHealth", "80", "maxHealth", "80"));
    }

    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class MutableApi
            extends BondedPanelTestFixtures.StubApi {
        private BondedCompanionProfileView profile;
        private Consumer<BondedCompanionChangedEvent> listener;
        private final AtomicInteger listCalls = new AtomicInteger();

        private MutableApi(BondedCompanionProfileView profile) {
            super(List.of(profile));
            this.profile = profile;
        }

        @Override
        public CompletableFuture<BondedCompanionResult<
                List<BondedCompanionProfileView>>> list(
                UUID owner, String roster) {
            listCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new BondedCompanionResult<>(
                            BondedCompanionResultCode.SUCCESS,
                            List.of(profile), null));
        }

        @Override
        public AutoCloseable subscribe(
                Consumer<BondedCompanionChangedEvent> listener) {
            this.listener = listener;
            return () -> this.listener = null;
        }

        private void fire(BondedCompanionChangedEvent event) {
            if (listener != null) listener.accept(event);
        }
    }
}
