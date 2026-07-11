package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Deterministic state routing for interrupted managed-coop lifecycle operations. */
class ManagedCoopLifecycleRecoveryPlannerTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private final ManagedCoopLifecycleRecoveryPlanner planner =
            new ManagedCoopLifecycleRecoveryPlanner();

    @Test
    void captureSlotAndRetirementStatesRouteToDifferentReplayBoundaries() {
        var slot = planner.plan(" WORLD ", List.of(), List.of(
                operation(OperationKind.CAPTURE, OperationState.SLOT_COMMITTED)));
        var retirement = planner.plan("world", List.of(), List.of(
                operation(OperationKind.CAPTURE, OperationState.SOURCE_RETIRE_REQUESTED)));

        assertEquals(
                ManagedCoopLifecycleRecoveryPlanner.ActionKind.REQUEST_CAPTURE_SOURCE_RETIREMENT,
                slot.kind());
        assertEquals(
                ManagedCoopLifecycleRecoveryPlanner.ActionKind.RESUME_CAPTURE_SOURCE_RETIREMENT,
                retirement.kind());
    }

    @Test
    void releaseWaitsForItsExactPhysicalCoopContext() throws Exception {
        OperationRecord operation = operation(
                OperationKind.RELEASE, OperationState.PROJECTION_CREATED);

        var waiting = planner.plan("world", List.of(), List.of(operation));
        var ready = planner.plan("world", List.of(context()), List.of(operation));

        assertEquals(ManagedCoopLifecycleRecoveryPlanner.ActionKind.WAIT_FOR_COOP_CONTEXT,
                waiting.kind());
        assertEquals(ManagedCoopLifecycleRecoveryPlanner.ActionKind.RESUME_RELEASE, ready.kind());
        assertEquals(AUTHORITY, ready.context().authorityKey());
    }

    @Test
    void capturePreparedFailsClosedAndOtherWorldIsIgnored() {
        var blocked = planner.plan("world", List.of(), List.of(
                operation(OperationKind.CAPTURE, OperationState.PREPARED)));
        var none = planner.plan("other", List.of(), List.of(
                operation(OperationKind.CAPTURE, OperationState.SOURCE_RETIRE_REQUESTED)));

        assertEquals(ManagedCoopLifecycleRecoveryPlanner.ActionKind.BLOCKED_UNSAFE_STATE,
                blocked.kind());
        assertEquals(ManagedCoopLifecycleRecoveryPlanner.ActionKind.NONE, none.kind());
        assertNull(none.operation());
    }

    @Test
    void selectionIsDeterministicEvenWhenCallerOrderIsNot() {
        OperationRecord later = operation(
                "later", new ManagedCoopAuthorityKey("world", 9, 2, 3),
                OperationKind.CAPTURE, OperationState.SOURCE_RETIRE_REQUESTED);
        OperationRecord first = operation(
                "first", AUTHORITY,
                OperationKind.CAPTURE, OperationState.SLOT_COMMITTED);

        var action = planner.plan("world", List.of(), List.of(later, first));

        assertEquals("first", action.operation().operationId());
        assertEquals(ManagedCoopLifecycleRecoveryPlanner.ActionKind.REQUEST_CAPTURE_SOURCE_RETIREMENT,
                action.kind());
    }

    private static OperationRecord operation(OperationKind kind, OperationState state) {
        return operation(kind.name().toLowerCase() + "-op", AUTHORITY, kind, state);
    }

    private static OperationRecord operation(String operationId,
                                              ManagedCoopAuthorityKey authority,
                                              OperationKind kind,
                                              OperationState state) {
        boolean capture = kind == OperationKind.CAPTURE;
        long generation = switch (state) {
            case PREPARED -> 0L;
            case SLOT_COMMITTED, SPAWN_CLAIMED -> 1L;
            case SOURCE_RETIRE_REQUESTED, PROJECTION_CREATED -> 2L;
            default -> 0L;
        };
        UUID source = capture ? new UUID(0L, 1L) : null;
        UUID planned = capture ? null : new UUID(0L, 2L);
        UUID actual = state == OperationState.PROJECTION_CREATED ? planned : null;
        return new OperationRecord(
                operationId, kind, "profile", authority,
                "coop_chicken", 0, source, planned, actual, state,
                "a".repeat(64), 0L, generation, 0, true,
                -100L, -90L, 0L, null);
    }

    private static ManagedCoopContext context() throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", "test");
        set(config, "enabled", true);
        set(config, "coopId", "coop_chicken");
        set(config.getIdentityRules(), "preserveUUID", false);
        return new ManagedCoopContext(AUTHORITY, "coop_chicken", 0, config, null);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
