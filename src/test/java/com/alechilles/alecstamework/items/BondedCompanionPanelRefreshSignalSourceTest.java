package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
        FakeEvents events = new FakeEvents();
        BondedCompanionPanelRefreshSignalSource source =
                new BondedCompanionPanelRefreshSignalSource(cache, events);
        List<LinkedPanelRefreshSignal> signals = new ArrayList<>();

        try (AutoCloseable ignored = source.forRoster(OWNER, ROSTER).subscribe(signals::add)) {
            events.emit(xpEvent(OWNER, NPC_ONE));
            events.emit(xpEvent(OTHER_OWNER, NPC_TWO));
            cache.emit();

            assertEquals(List.of(
                    LinkedPanelRefreshSignal.Kind.PROGRESSION,
                    LinkedPanelRefreshSignal.Kind.IMMEDIATE), kinds(signals));
        }
    }

    @Test
    void closesCacheAndEventSubscriptionsExactlyOnce() throws Exception {
        FakeCache cache = new FakeCache();
        FakeEvents events = new FakeEvents();
        BondedCompanionPanelRefreshSignalSource source =
                new BondedCompanionPanelRefreshSignalSource(cache, events);
        AutoCloseable subscription = source.forRoster(OWNER, ROSTER).subscribe(ignored -> { });

        subscription.close();
        subscription.close();

        assertEquals(1, cache.closeCount.get());
        assertEquals(1, events.closeCount.get());
    }

    private static List<LinkedPanelRefreshSignal.Kind> kinds(
            List<LinkedPanelRefreshSignal> signals) {
        return signals.stream().map(LinkedPanelRefreshSignal::kind).toList();
    }

    private static CompanionXpAwardedEvent xpEvent(UUID ownerUuid, UUID npcUuid) {
        return new CompanionXpAwardedEvent(npcUuid, ownerUuid, Set.of(), null,
                null, CompanionXpSource.CUSTOM, 1.0, 1, 1,
                0.0, 1.0, 0.0, 1.0, 2.0, 10, false,
                false, 1L, 1L);
    }

    private static final class FakeCache
            implements BondedCompanionPanelRefreshSignalSource.CacheSubscriptions {
        private Runnable listener;
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public AutoCloseable subscribe(UUID ownerUuid, String rosterId, Runnable listener) {
            this.listener = listener;
            return closeCount::incrementAndGet;
        }

        private void emit() {
            listener.run();
        }
    }

    private static final class FakeEvents implements TameworkEventsApi {
        private Consumer<CompanionXpAwardedEvent> listener;
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        @SuppressWarnings("unchecked")
        public <E extends TameworkEvent> AutoCloseable subscribe(
                Class<E> type, Consumer<E> listener) {
            this.listener = (Consumer<CompanionXpAwardedEvent>) listener;
            return closeCount::incrementAndGet;
        }

        private void emit(CompanionXpAwardedEvent event) {
            listener.accept(event);
        }
    }
}
