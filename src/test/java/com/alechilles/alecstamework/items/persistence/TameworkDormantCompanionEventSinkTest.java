package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.NpcDeathRecordedEvent;
import com.alechilles.alecstamework.api.NpcLostRecordedEvent;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies replacement dormant evidence maps to the released public events. */
class TameworkDormantCompanionEventSinkTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "72000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias SOURCE = new NpcAlias(UUID.fromString(
            "72000000-0000-0000-0000-000000000002"
    ));

    @Test
    void mapsDeathAndLostWithoutLegacyServiceSnapshotTypes() {
        AtomicReference<NpcDeathRecordedEvent> deathEvent =
                new AtomicReference<>();
        AtomicReference<NpcLostRecordedEvent> lostEvent =
                new AtomicReference<>();
        TameworkDormantCompanionEventSink sink =
                new TameworkDormantCompanionEventSink(
                        deathEvent::set, lostEvent::set
                );
        DormantCompanionEventFacts facts =
                new DormantCompanionEventFacts(
                        SOURCE.value(),
                        UUID.fromString(
                                "72000000-0000-0000-0000-000000000003"
                        ),
                        "Owner",
                        Set.of("tool-b", "tool-a"),
                        "snapshot_role",
                        "Ember",
                        true,
                        new DormantCompanionObservation.PositionObservation(
                                4.0, 5.0, 6.0
                        )
                );

        DormantCompanionObservation death = observation(
                DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT
        );
        sink.publish(new DormantCompanionEventSink.Published(
                death,
                facts,
                profile(LifecycleState.DEAD_REVIVABLE),
                -100L
        ));

        assertEquals(SOURCE.value(), deathEvent.get().npcUuid());
        assertEquals("canonical_role", deathEvent.get().roleId());
        assertEquals("Display", deathEvent.get().displayName());
        assertEquals("Ember", deathEvent.get().customName());
        assertEquals(-300L, deathEvent.get().respawnAvailableAtMs());
        assertEquals(4.0, deathEvent.get().homePosition().x());
        assertNull(deathEvent.get().profile().currentNpcUuid());

        DormantCompanionObservation lost = observation(
                DormantCompanionObservation.Evidence.DESTRUCTIVE_REMOVAL
        );
        sink.publish(new DormantCompanionEventSink.Published(
                lost,
                facts,
                profile(LifecycleState.LOST),
                -90L
        ));

        assertEquals(-510L, lostEvent.get().lastRelocationQueuedAtMs());
        assertEquals(-500L, lostEvent.get().lostAtMs());
        assertEquals(2, lostEvent.get().relocationRetryAttempts());
        assertEquals(1.0, lostEvent.get().lastKnownPosition().x());
        assertEquals(-90L, lostEvent.get().emittedAtMs());
    }

    private DormantCompanionObservation observation(
            DormantCompanionObservation.Evidence evidence
    ) {
        return new DormantCompanionObservation(
                "event-" + evidence.name(),
                PROFILE,
                SOURCE,
                "world",
                evidence,
                "receipt-" + evidence.name(),
                -500L,
                new DormantCompanionObservation.PositionObservation(
                        1.0, 2.0, 3.0
                ),
                evidence == DormantCompanionObservation.Evidence
                        .SAVED_DEATH_COMPONENT
                        ? new DormantCompanionObservation.DeathObservation(
                        -490L,
                        -300L,
                        DeathSnapshotV2Payload.DeathCauseKind.NPC,
                        "Razorbeak"
                )
                        : null,
                evidence == DormantCompanionObservation.Evidence
                        .SAVED_DEATH_COMPONENT
                        ? null
                        : new DormantCompanionObservation.LostObservation(
                                -510L, 2
                        )
        );
    }

    private CompanionProfileReadModel profile(LifecycleState state) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Display",
                "canonical_role",
                null,
                null,
                "world",
                -1_000L,
                -900L,
                -800L,
                1L
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                null,
                state,
                LifecycleLocation.none(),
                new LifecycleRevision(8),
                null,
                -500L,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        return new CompanionProfileReadModel(
                identity, null, lifecycle, List.of(), List.of(), null
        );
    }
}
