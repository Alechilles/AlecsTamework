package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandSavedNpcPanelCacheTest {
    @Test
    void pendingReadDoesNotBlockCardsAndCompletionRefreshesOnlyItsOwner() throws Exception {
        var read = new CompletableFuture<CommandSavedNpcPanelSnapshot>();
        AtomicInteger calls = new AtomicInteger();
        var cache = new CommandSavedNpcPanelCache(id -> { calls.incrementAndGet(); return read; });
        var owner = UUID.randomUUID();
        var profile = new ProfileId(UUID.randomUUID());
        AtomicInteger signals = new AtomicInteger();
        AtomicInteger unrelated = new AtomicInteger();
        var subscription = cache.signals(owner).subscribe(signal -> signals.incrementAndGet());
        cache.signals(UUID.randomUUID()).subscribe(signal -> unrelated.incrementAndGet());
        assertNull(cache.peek(profile, owner, new CommandSavedNpcPanelCache.Revision(1L, "checkpoint", 1L)));
        assertNull(cache.peek(profile, owner, new CommandSavedNpcPanelCache.Revision(1L, "checkpoint", 1L)));
        assertEquals(1, calls.get());
        assertFalse(read.isDone());
        read.complete(null);
        assertEquals(1, signals.get());
        assertEquals(0, unrelated.get());
        subscription.close();
        cache.close();
        assertNull(cache.peek(new ProfileId(UUID.randomUUID()), owner, new CommandSavedNpcPanelCache.Revision(1L, "checkpoint", 1L)));
        assertEquals(1, calls.get());
    }

    @Test
    void missingSnapshotRetriesAfterBackoffWithoutQueryingEveryRefresh() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        var cache = new CommandSavedNpcPanelCache(id -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, clock::get);
        var owner = UUID.randomUUID();
        var profile = new ProfileId(UUID.randomUUID());
        cache.peek(profile, owner, new CommandSavedNpcPanelCache.Revision(1L, "checkpoint", 1L));
        cache.peek(profile, owner, new CommandSavedNpcPanelCache.Revision(1L, "checkpoint", 1L));
        assertEquals(1, calls.get());
        clock.set(10_000_000_000L);
        cache.peek(profile, owner, new CommandSavedNpcPanelCache.Revision(1L, "checkpoint", 1L));
        assertEquals(2, calls.get());
        cache.peek(profile, owner, new CommandSavedNpcPanelCache.Revision(1L, "checkpoint", 2L));
        assertEquals(3, calls.get(), "A new checkpoint must refresh even when profile metadata is unchanged");
        cache.close();
    }
}
