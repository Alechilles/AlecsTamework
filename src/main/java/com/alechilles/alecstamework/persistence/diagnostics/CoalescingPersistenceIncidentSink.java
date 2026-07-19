package com.alechilles.alecstamework.persistence.diagnostics;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/** Throttles repeat-only records while preserving an aggregate repeat count. */
public final class CoalescingPersistenceIncidentSink implements PersistenceIncidentSink {
    static final long DEFAULT_REPEAT_INTERVAL_MS = 60_000L;

    private final PersistenceIncidentSink delegate;
    private final Clock clock;
    private final long intervalMs;
    private final ConcurrentHashMap<String, RepeatState> repeats = new ConcurrentHashMap<>();

    public CoalescingPersistenceIncidentSink(@Nonnull PersistenceIncidentSink delegate) {
        this(delegate, Clock.systemUTC(), DEFAULT_REPEAT_INTERVAL_MS);
    }

    CoalescingPersistenceIncidentSink(PersistenceIncidentSink delegate, Clock clock, long intervalMs) {
        this.delegate = delegate;
        this.clock = clock;
        this.intervalMs = Math.max(1L, intervalMs);
    }

    @Override
    public void record(@Nonnull PersistenceIncidentEvent event) {
        if (event.eventKind() != PersistenceIncidentEventKind.INCIDENT_REPEATED) {
            delegate.record(event);
            return;
        }
        long now = clock.millis();
        repeats.compute(event.incidentId(), (ignored, current) -> {
            RepeatState next = current == null
                    ? new RepeatState(1L, 0L)
                    : new RepeatState(current.pendingCount() + 1L, current.lastEmittedAtMs());
            if (now - next.lastEmittedAtMs() < intervalMs) return next;
            delegate.record(withRepeatCount(event, next.pendingCount(), now));
            return new RepeatState(0L, now);
        });
    }

    private PersistenceIncidentEvent withRepeatCount(PersistenceIncidentEvent event,
                                                      long repeatCount,
                                                      long now) {
        return new PersistenceIncidentEvent(
                event.formatVersion(), now, event.eventKind(), event.bootId(), event.incidentId(),
                event.traceId(), event.operationId(), event.domain(), event.phase(), event.reasonCode(),
                event.failureClass(), event.disposition(), event.scopes(), repeatCount,
                event.recoveryAttempt(), event.result());
    }

    private record RepeatState(long pendingCount, long lastEmittedAtMs) {
    }
}
