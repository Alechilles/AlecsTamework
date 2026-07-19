package com.alechilles.alecstamework.persistence.health;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceStorageHealthServiceTest {
    @Test
    void retryingKeepsWriterAuthorityAndRecoveryRequiresReadOnly() {
        List<PersistenceStorageState> transitions = new ArrayList<>();
        PersistenceStorageHealthService service = new PersistenceStorageHealthService(
                state -> transitions.add(state.status()));

        assertTrue(service.enterRetrying("sqlite_busy"));
        assertTrue(service.acceptsWrites());
        assertFalse(service.beginRecovery());
        assertTrue(service.finishRetry());
        assertEquals(PersistenceStorageState.HEALTHY, service.getState().status());

        assertTrue(service.enterReadOnly("disk_full", "incident-1"));
        assertFalse(service.acceptsWrites());
        assertFalse(service.enterReadOnly("later", "incident-2"));
        assertEquals("disk_full", service.getState().reason());
        assertEquals("incident-1", service.getState().incidentId());
        assertTrue(service.beginRecovery());
        assertTrue(service.completeRecovery());
        assertEquals(List.of(
                PersistenceStorageState.RETRYING,
                PersistenceStorageState.HEALTHY,
                PersistenceStorageState.READ_ONLY,
                PersistenceStorageState.RECOVERING,
                PersistenceStorageState.HEALTHY
        ), transitions);
    }

    @Test
    void diagnosticListenerCannotBreakTheStorageFence() {
        PersistenceStorageHealthService service = new PersistenceStorageHealthService(state -> {
            throw new IllegalStateException("sink failed");
        });
        assertTrue(service.enterReadOnly("io_failure", null));
        assertEquals(PersistenceStorageState.READ_ONLY, service.getState().status());
    }
}
