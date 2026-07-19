package com.alechilles.alecstamework.persistence.incidents;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceQuarantineRegistryTest {
    @Test
    void reloadPublishesOnlyActiveLatestGenerationAndVerifiedClearUsesEvidence() {
        PersistenceScope scope = new PersistenceScope(PersistenceScopeType.PROFILE, "profile-a", "hash-a", null);
        PersistenceQuarantineRegistry registry = new PersistenceQuarantineRegistry();
        registry.reload(List.of(
                record("q-old", scope, PersistenceQuarantineState.ACTIVE, 1L, "old"),
                record("q-new", scope, PersistenceQuarantineState.VERIFYING, 2L, "new"),
                record("q-cleared", new PersistenceScope(PersistenceScopeType.PROFILE, "profile-b", "hash-b", null),
                        PersistenceQuarantineState.CLEARED, 3L, "cleared")
        ));

        assertEquals("q-new", registry.find(scope).orElseThrow().quarantineId());
        assertEquals(1, registry.size());
        assertFalse(registry.clearVerified("q-new", 1L, "new"));
        assertFalse(registry.clearVerified("q-new", 2L, "old"));
        assertTrue(registry.clearVerified("q-new", 2L, "new"));
        assertTrue(registry.find(scope).isEmpty());
    }

    private PersistenceQuarantineRecord record(String id, PersistenceScope scope,
                                               PersistenceQuarantineState state,
                                               long generation, String evidence) {
        return new PersistenceQuarantineRecord(
                id, "incident", scope, PersistenceDomain.OWNER_MUTATION, "reason",
                state, evidence, generation, 1L, generation, 0L, null
        );
    }
}
