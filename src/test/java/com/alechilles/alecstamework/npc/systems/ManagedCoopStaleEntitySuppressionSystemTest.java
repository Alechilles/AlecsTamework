package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Decision;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.ACTIVE_RELEASE_PROJECTION;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.HOUSED_ALIAS;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.UNRELATED_NPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavioral boundary coverage for actionable stale-entity suppression. */
class ManagedCoopStaleEntitySuppressionSystemTest {
    @Test
    void onlySuppressStartsDespawnAndEmitsOneTransitionEvent() {
        List<ManagedCoopStaleEntitySuppressionSystem.SuppressionEvent> events = new ArrayList<>();
        ManagedCoopStaleEntitySuppressionSystem system = system(events::add);
        Observation observation = Observation.of(uuid(1L), null);
        NPCEntity ignored = new NPCEntity();
        NPCEntity allowed = new NPCEntity();
        NPCEntity suppressed = new NPCEntity();

        assertFalse(system.applyDecision(
                ignored,
                observation,
                decision(Action.IGNORE, UNRELATED_NPC)
        ));
        assertFalse(system.applyDecision(
                allowed,
                observation,
                decision(Action.ALLOW, ACTIVE_RELEASE_PROJECTION)
        ));
        assertTrue(system.applyDecision(
                suppressed,
                observation,
                decision(Action.SUPPRESS, HOUSED_ALIAS)
        ));
        assertFalse(system.applyDecision(
                suppressed,
                observation,
                decision(Action.SUPPRESS, HOUSED_ALIAS)
        ));

        assertFalse(ignored.isDespawning());
        assertFalse(allowed.isDespawning());
        assertTrue(suppressed.isDespawning());
        assertEquals(1, events.size());
        assertEquals(HOUSED_ALIAS, events.getFirst().reason());
        assertEquals(observation.npcUuid(), events.getFirst().npcUuid());
    }

    @Test
    void diagnosticSinkFailureCannotUndoOrCrashSuppression() {
        ManagedCoopStaleEntitySuppressionSystem system = system(event -> {
            throw new IllegalStateException("simulated sink failure");
        });
        NPCEntity stale = new NPCEntity();

        boolean applied = system.applyDecision(
                stale,
                Observation.of(uuid(2L), null),
                decision(Action.SUPPRESS, HOUSED_ALIAS)
        );

        assertTrue(applied);
        assertTrue(stale.isDespawning());
    }

    private static ManagedCoopStaleEntitySuppressionSystem system(
            ManagedCoopStaleEntitySuppressionSystem.DecisionSink sink) {
        return new ManagedCoopStaleEntitySuppressionSystem(
                observation -> decision(Action.IGNORE, UNRELATED_NPC),
                null,
                null,
                null,
                sink
        );
    }

    private static Decision decision(Action action,
                                     ManagedCoopStaleEntityPolicy.Reason reason) {
        return new Decision(action, reason, "profile-a", "operation-a");
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
