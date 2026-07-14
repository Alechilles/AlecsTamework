package com.alechilles.alecstamework.items;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRelocationChunkLeaseServiceTest {

    @Test
    void duplicateChunkCompletionRetainsAndReleasesExactlyOnce() {
        AtomicInteger retained = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        CommandRelocationChunkLeaseService<Object, Object> leases =
                new CommandRelocationChunkLeaseService<>(
                        ignored -> retained.incrementAndGet(),
                        ignored -> released.incrementAndGet()
                );
        Object pending = new Object();
        Object chunk = new Object();

        assertTrue(leases.open(pending));
        assertTrue(leases.retain(pending, chunk));
        assertTrue(leases.retain(pending, chunk));
        assertEquals(1, retained.get());

        leases.release(pending);

        assertEquals(1, released.get());
        assertEquals(0, leases.activeScopeCount());
        assertFalse(leases.retain(pending, chunk));
    }

    @Test
    void closeReleasesEveryOpenScopeAndRejectsLateCompletions() {
        AtomicInteger retained = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        CommandRelocationChunkLeaseService<Object, Object> leases =
                new CommandRelocationChunkLeaseService<>(
                        ignored -> retained.incrementAndGet(),
                        ignored -> released.incrementAndGet()
                );
        Object firstPending = new Object();
        Object secondPending = new Object();
        Object firstChunk = new Object();
        Object secondChunk = new Object();

        assertTrue(leases.open(firstPending));
        assertTrue(leases.open(secondPending));
        assertTrue(leases.retain(firstPending, firstChunk));
        assertTrue(leases.retain(secondPending, secondChunk));

        leases.close();

        assertEquals(2, retained.get());
        assertEquals(2, released.get());
        assertEquals(0, leases.activeScopeCount());
        assertFalse(leases.open(new Object()));
        assertFalse(leases.retain(firstPending, firstChunk));
    }
}
