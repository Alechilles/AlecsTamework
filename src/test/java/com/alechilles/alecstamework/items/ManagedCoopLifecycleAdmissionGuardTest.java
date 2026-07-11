package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the slot-commit/source-retirement release race. */
class ManagedCoopLifecycleAdmissionGuardTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void activeCaptureBlocksTheWholeAuthorityUntilSourceRetirementCompletes() throws Exception {
        ManagedCoopLifecycleOperationIndex index = index(List.of(captureOperation()));
        ManagedCoopLifecycleAdmissionGuard guard =
                new ManagedCoopLifecycleAdmissionGuard(index, () -> true);

        var decision = guard.inspect(context());

        assertFalse(decision.allowed());
        assertEquals(ManagedCoopLifecycleAdmissionGuard.Status.BLOCKED_ACTIVE_OPERATION,
                decision.status());
        assertEquals("capture-op", decision.operationId());
    }

    @Test
    void emptyTrustedEpochAllowsNormalWorkAndTrustLossFailsClosed() throws Exception {
        ManagedCoopLifecycleOperationIndex index = index(List.of());
        AtomicBoolean composite = new AtomicBoolean(true);
        ManagedCoopLifecycleAdmissionGuard guard =
                new ManagedCoopLifecycleAdmissionGuard(index, composite::get);

        assertTrue(guard.inspect(context()).allowed());
        composite.set(false);
        assertEquals(ManagedCoopLifecycleAdmissionGuard.Status.BLOCKED_UNTRUSTED,
                guard.inspect(context()).status());
    }

    private static ManagedCoopLifecycleOperationIndex index(List<OperationRecord> operations) {
        ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();
        assertTrue(index.rebuild(ManagedCoopReadResult.loaded(operations)).rebuilt());
        return index;
    }

    private static OperationRecord captureOperation() {
        UUID source = new UUID(0L, 1L);
        return new OperationRecord(
                "capture-op", OperationKind.CAPTURE, "profile", AUTHORITY,
                "coop_chicken", 0, source, null, null,
                OperationState.SOURCE_RETIRE_REQUESTED, "a".repeat(64),
                0L, 2L, 0, true, -100L, -90L, 0L, null);
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
