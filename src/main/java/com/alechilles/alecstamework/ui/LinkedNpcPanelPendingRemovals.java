package com.alechilles.alecstamework.ui;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Page-local optimistic presentation; saved companion state remains authoritative. */
final class LinkedNpcPanelPendingRemovals {
    private static final long GRACE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Map<UUID, Long> deadlines = new HashMap<>();
    private final LongSupplier clock;

    LinkedNpcPanelPendingRemovals() { this(System::nanoTime); }
    LinkedNpcPanelPendingRemovals(LongSupplier clock) { this.clock = clock; }

    void hide(UUID npcUuid) {
        deadlines.putIfAbsent(npcUuid, clock.getAsLong() + GRACE_NANOS);
    }

    LinkedNpcEntry[] filter(LinkedNpcEntry[] entries) {
        expire();
        return deadlines.isEmpty() ? entries : Arrays.stream(entries)
                .filter(entry -> !deadlines.containsKey(entry.npcUuid()))
                .toArray(LinkedNpcEntry[]::new);
    }

    long remainingMillis() {
        expire();
        long now = clock.getAsLong();
        return deadlines.values().stream().mapToLong(deadline ->
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - now)))
                .min().orElse(Long.MAX_VALUE);
    }

    private void expire() {
        long now = clock.getAsLong();
        deadlines.values().removeIf(deadline -> now - deadline >= 0);
    }
}
