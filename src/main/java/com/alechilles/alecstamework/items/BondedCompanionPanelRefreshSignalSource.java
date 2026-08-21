package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionProgressionSignalBus;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignal;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignalSource;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** Creates owner-and-roster scoped refresh sources for bonded companion panels. */
final class BondedCompanionPanelRefreshSignalSource {
    private final CacheSubscriptions cacheSubscriptions;
    @Nullable private final CompanionProgressionSignalBus progressionSignals;

    BondedCompanionPanelRefreshSignalSource(BondedCompanionPanelLifecycle lifecycle,
                                            @Nullable CompanionProgressionSignalBus progressionSignals) {
        this(lifecycle::subscribe, progressionSignals);
    }

    BondedCompanionPanelRefreshSignalSource(CacheSubscriptions cacheSubscriptions,
                                            @Nullable CompanionProgressionSignalBus progressionSignals) {
        this.cacheSubscriptions = Objects.requireNonNull(cacheSubscriptions, "cacheSubscriptions");
        this.progressionSignals = progressionSignals;
    }

    LinkedPanelRefreshSignalSource forRoster(UUID ownerUuid, String rosterId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(rosterId, "rosterId");
        return listener -> subscribe(ownerUuid, rosterId, listener);
    }

    private AutoCloseable subscribe(UUID ownerUuid, String rosterId,
                                    Consumer<LinkedPanelRefreshSignal> listener) {
        Objects.requireNonNull(listener, "listener");
        AutoCloseable cache = cacheSubscriptions.subscribe(ownerUuid, rosterId,
                () -> listener.accept(new LinkedPanelRefreshSignal(
                        LinkedPanelRefreshSignal.Kind.IMMEDIATE)));
        AutoCloseable progression = progressionSignals == null ? () -> { } : progressionSignals.subscribe(
                transition -> {
                    if (ownerUuid.equals(transition.ownerUuid())) {
                        listener.accept(new LinkedPanelRefreshSignal(
                                LinkedPanelRefreshSignal.Kind.PROGRESSION));
                    }
                });
        return closeOnce(cache, progression);
    }

    private static AutoCloseable closeOnce(AutoCloseable first, AutoCloseable second) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            try {
                first.close();
            } finally {
                second.close();
            }
        };
    }

    @FunctionalInterface
    interface CacheSubscriptions {
        AutoCloseable subscribe(UUID ownerUuid, String rosterId, Runnable listener);
    }
}
