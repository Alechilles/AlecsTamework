package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-match contract for generic persistence retirement suppression evidence. */
class TameworkPersistenceRetirementComponentTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");

    @Test
    void markerMatchesOnlyTheExactProfileOperationAndKind() {
        OperationEnvelope expected = operation(
                "60000000-0000-0000-0000-000000000001",
                "companion_coop_capture"
        );
        TameworkPersistenceRetirementComponent marker =
                TameworkPersistenceRetirementComponent.exact(
                        PROFILE, expected
                );

        assertTrue(marker.matches(PROFILE, expected));
        assertFalse(marker.matches(PROFILE, operation(
                "60000000-0000-0000-0000-000000000002",
                "companion_coop_capture"
        )));
        assertFalse(marker.matches(PROFILE, operation(
                "60000000-0000-0000-0000-000000000001",
                "companion_capture"
        )));

        TameworkPersistenceRetirementComponent clone = marker.clone();
        assertNotSame(marker, clone);
        assertTrue(clone.matches(PROFILE, expected));
    }

    private OperationEnvelope operation(String operationId, String kind) {
        OperationId id = OperationId.parse(operationId);
        return new OperationEnvelope(
                id,
                new IdempotencyKey("retirement-" + operationId + "-" + kind),
                new OperationKind(kind),
                1,
                "{}",
                OperationPhase.LIVE_APPLYING,
                kind,
                LifecycleRevision.INITIAL,
                null,
                0,
                0,
                null,
                null,
                -600,
                -500,
                null,
                null,
                null,
                List.of(OperationScope.operation(id))
        );
    }
}
