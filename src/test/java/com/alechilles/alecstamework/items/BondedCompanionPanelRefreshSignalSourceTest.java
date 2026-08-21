package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSignalBus;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSignalTestSupport;
import com.alechilles.alecstamework.npc.progression.CompanionXpTransition;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignal;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BondedCompanionPanelRefreshSignalSourceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NPC_ONE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NPC_TWO = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ROSTER = "test:roster";

    @Test
    void emitsScopedCacheAndOwnerProgressionSignals() throws Exception {
        FakeCache cache = new FakeCache();
        CompanionProgressionSignalBus events = new CompanionProgressionSignalBus();
        BondedCompanionPanelRefreshSignalSource source =
                new BondedCompanionPanelRefreshSignalSource(cache, events);
        List<LinkedPanelRefreshSignal> signals = new ArrayList<>();

        try (AutoCloseable ignored = source.forRoster(OWNER, ROSTER).subscribe(signals::add)) {
            CompanionProgressionSignalTestSupport.publish(events, xpEvent(OWNER, NPC_ONE));
            CompanionProgressionSignalTestSupport.publish(events, xpEvent(OTHER_OWNER, NPC_TWO));
            cache.emit();

            assertEquals(List.of(
                    LinkedPanelRefreshSignal.Kind.PROGRESSION,
                    LinkedPanelRefreshSignal.Kind.IMMEDIATE), kinds(signals));
            assertEquals(OWNER, cache.ownerUuid);
            assertEquals(ROSTER, cache.rosterId);
        }
    }

    @Test
    void nullEventsStillDeliversCacheSignalsAndClosesCacheOnce() throws Exception {
        FakeCache cache = new FakeCache();
        BondedCompanionPanelRefreshSignalSource source =
                new BondedCompanionPanelRefreshSignalSource(cache, null);
        List<LinkedPanelRefreshSignal> signals = new ArrayList<>();
        AutoCloseable subscription = source.forRoster(OWNER, ROSTER).subscribe(signals::add);

        cache.emit();
        subscription.close();
        subscription.close();

        assertEquals(List.of(LinkedPanelRefreshSignal.Kind.IMMEDIATE), kinds(signals));
        assertEquals(1, cache.closeCount.get());
    }

    @Test
    void closesSecondChildOnceWhenFirstCloseThrows() throws Exception {
        FakeCache cache = new FakeCache();
        cache.throwOnClose = true;
        CompanionProgressionSignalBus events = new CompanionProgressionSignalBus();
        BondedCompanionPanelRefreshSignalSource source =
                new BondedCompanionPanelRefreshSignalSource(cache, events);
        List<LinkedPanelRefreshSignal> signals = new ArrayList<>();
        AutoCloseable subscription = source.forRoster(OWNER, ROSTER).subscribe(signals::add);

        assertThrows(IllegalStateException.class, subscription::close);
        subscription.close();

        assertEquals(1, cache.closeCount.get());
        CompanionProgressionSignalTestSupport.publish(events, xpEvent(OWNER, NPC_ONE));
        assertEquals(List.of(), kinds(signals));
    }

    @Test
    void routesBondedRostersToScopedSourceAndGenericRostersToNone() throws Exception {
        FakeCache cache = new FakeCache();
        CommandSelectionPageService service = new CommandSelectionPageService(
                null, null, null, null, null, null, null, null, null,
                null, null, new BondedCompanionPanelRefreshSignalSource(cache, null));

        try (AutoCloseable ignored = service.pageSignals(OWNER, bondedConfig())
                .subscribe(signal -> { })) {
            assertEquals(OWNER, cache.ownerUuid);
            assertEquals(ROSTER, cache.rosterId);
        }
        try (AutoCloseable ignored = service.pageSignals(OWNER, genericConfig())
                .subscribe(signal -> { })) {
            assertEquals(1, cache.subscribeCount.get());
        }
    }

    @Test
    void closesCacheAndEventSubscriptionsExactlyOnce() throws Exception {
        FakeCache cache = new FakeCache();
        CompanionProgressionSignalBus events = new CompanionProgressionSignalBus();
        BondedCompanionPanelRefreshSignalSource source =
                new BondedCompanionPanelRefreshSignalSource(cache, events);
        List<LinkedPanelRefreshSignal> signals = new ArrayList<>();
        AutoCloseable subscription = source.forRoster(OWNER, ROSTER).subscribe(signals::add);

        subscription.close();
        subscription.close();

        assertEquals(1, cache.closeCount.get());
        CompanionProgressionSignalTestSupport.publish(events, xpEvent(OWNER, NPC_ONE));
        assertEquals(List.of(), kinds(signals));
    }

    private static List<LinkedPanelRefreshSignal.Kind> kinds(
            List<LinkedPanelRefreshSignal> signals) {
        return signals.stream().map(LinkedPanelRefreshSignal::kind).toList();
    }

    private static CompanionXpTransition xpEvent(UUID ownerUuid, UUID npcUuid) {
        return new CompanionXpTransition(npcUuid, ownerUuid, Set.of(), null,
                null, com.alechilles.alecstamework.api.CompanionXpSource.CUSTOM,
                1.0, 1, 1, 0.0, 1.0, 0.0, 1.0, 2.0, 10,
                false, false, 1L, 1L);
    }

    private static TwCommandItemConfig bondedConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {"RosterStorage":"BondedCompanions","BondedRosterId":"test:roster"}
                """), new ExtraInfo());
    }

    private static TwCommandItemConfig genericConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("{}"), new ExtraInfo());
    }

    private static final class FakeCache
            implements BondedCompanionPanelRefreshSignalSource.CacheSubscriptions {
        private Runnable listener;
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger subscribeCount = new AtomicInteger();
        private UUID ownerUuid;
        private String rosterId;
        private boolean throwOnClose;

        @Override
        public AutoCloseable subscribe(UUID ownerUuid, String rosterId, Runnable listener) {
            this.ownerUuid = ownerUuid;
            this.rosterId = rosterId;
            this.listener = listener;
            subscribeCount.incrementAndGet();
            return () -> {
                closeCount.incrementAndGet();
                if (throwOnClose) throw new IllegalStateException("cache close");
            };
        }

        private void emit() {
            listener.run();
        }
    }
}
