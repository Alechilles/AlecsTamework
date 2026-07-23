package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exhaustive contract tests for operation identities, scopes, and shared phase transitions. */
class OperationProtocolTest {
    @Test
    void phaseGraphContainsOnlyTheAcceptedSharedEdges() {
        Set<Edge> allowed = Set.of(
                edge(OperationPhase.PREPARED, OperationPhase.LIVE_APPLYING),
                edge(OperationPhase.PREPARED, OperationPhase.DURABLE),
                edge(OperationPhase.PREPARED, OperationPhase.FAILED),
                edge(OperationPhase.LIVE_APPLYING, OperationPhase.DURABLE),
                edge(OperationPhase.LIVE_APPLYING, OperationPhase.RETRYABLE),
                edge(OperationPhase.LIVE_APPLYING, OperationPhase.COMPENSATING),
                edge(OperationPhase.LIVE_APPLYING, OperationPhase.UNKNOWN),
                edge(OperationPhase.DURABLE, OperationPhase.PUBLISHED),
                edge(OperationPhase.DURABLE, OperationPhase.RETRYABLE),
                edge(OperationPhase.COMPENSATING, OperationPhase.COMPENSATED),
                edge(OperationPhase.COMPENSATING, OperationPhase.RETRYABLE),
                edge(OperationPhase.COMPENSATING, OperationPhase.UNKNOWN),
                edge(OperationPhase.RETRYABLE, OperationPhase.LIVE_APPLYING),
                edge(OperationPhase.RETRYABLE, OperationPhase.DURABLE),
                edge(OperationPhase.RETRYABLE, OperationPhase.COMPENSATING),
                edge(OperationPhase.RETRYABLE, OperationPhase.FAILED),
                edge(OperationPhase.UNKNOWN, OperationPhase.DURABLE),
                edge(OperationPhase.UNKNOWN, OperationPhase.COMPENSATING),
                edge(OperationPhase.UNKNOWN, OperationPhase.FAILED)
        );

        for (OperationPhase from : OperationPhase.values()) {
            for (OperationPhase to : OperationPhase.values()) {
                assertEquals(allowed.contains(edge(from, to)), from.canTransitionTo(to), from + " -> " + to);
            }
        }

        assertEquals(
                EnumSet.of(OperationPhase.PUBLISHED, OperationPhase.COMPENSATED, OperationPhase.FAILED),
                terminalPhases()
        );
        assertThrows(IllegalArgumentException.class,
                () -> OperationPhase.PUBLISHED.requireTransitionTo(OperationPhase.DURABLE));
    }

    @Test
    void operationIdentityAndKindAreCanonical() {
        OperationId id = OperationId.create();
        assertEquals(id, OperationId.parse(id.toString()));
        assertNotEquals(id, OperationId.create());
        assertEquals("capture_profile", new OperationKind("capture_profile").toString());
        assertThrows(IllegalArgumentException.class, () -> new OperationKind("Capture-Profile"));
        assertThrows(IllegalArgumentException.class, () -> OperationId.parse("not-a-uuid"));
    }

    @Test
    void idempotencyAndScopesRejectAmbiguousRepresentations() {
        OperationId operationId = OperationId.parse("40000000-0000-0000-0000-000000000001");
        ProfileId profileId = ProfileId.parse("20000000-0000-0000-0000-000000000001");
        OwnerId ownerId = OwnerId.parse("10000000-0000-0000-0000-000000000001");

        assertEquals(operationId.toString(), OperationScope.operation(operationId).key());
        assertEquals(profileId.toString(), OperationScope.profile(profileId).key());
        assertEquals(ownerId.toString(), OperationScope.owner(ownerId).key());
        assertEquals("*", OperationScope.global().key());
        assertEquals("capture:source-a", new IdempotencyKey(" capture:source-a ").value());

        assertThrows(IllegalArgumentException.class,
                () -> new OperationScope(OperationScopeType.GLOBAL, "other"));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationScope(OperationScopeType.PROFILE, "*"));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyKey("x".repeat(IdempotencyKey.MAX_LENGTH + 1)));
    }

    private static Set<OperationPhase> terminalPhases() {
        Set<OperationPhase> terminal = EnumSet.noneOf(OperationPhase.class);
        for (OperationPhase phase : OperationPhase.values()) {
            if (phase.isTerminal()) {
                terminal.add(phase);
            }
        }
        return terminal;
    }

    private static Edge edge(OperationPhase from, OperationPhase to) {
        return new Edge(from, to);
    }

    private record Edge(OperationPhase from, OperationPhase to) {
    }
}
