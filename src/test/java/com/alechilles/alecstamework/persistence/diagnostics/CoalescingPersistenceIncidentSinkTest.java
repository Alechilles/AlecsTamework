package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoalescingPersistenceIncidentSinkTest {
    @Test
    void repeatedIncidentsAreThrottledWithoutLosingAggregateCount() {
        MutableClock clock = new MutableClock();
        List<PersistenceIncidentEvent> recorded = new ArrayList<>();
        CoalescingPersistenceIncidentSink sink =
                new CoalescingPersistenceIncidentSink(recorded::add, clock, 1_000L);

        sink.record(event());
        sink.record(event());
        sink.record(event());
        clock.advance(1_000L);
        sink.record(event());

        assertEquals(2, recorded.size());
        assertEquals(1L, recorded.get(0).repeatCount());
        assertEquals(3L, recorded.get(1).repeatCount());
    }

    private PersistenceIncidentEvent event() {
        return new PersistenceIncidentEvent(
                1, 1L, PersistenceIncidentEventKind.INCIDENT_REPEATED,
                "boot", "incident", null, "operation", PersistenceDomain.OWNER_MUTATION,
                PersistenceOperationPhase.PUBLICATION, "publication_failed",
                PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE,
                PersistenceDisposition.SCOPED_QUARANTINE, List.of(), 1L, 0L, null);
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_000L;

        private void advance(long amount) {
            millis += amount;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
