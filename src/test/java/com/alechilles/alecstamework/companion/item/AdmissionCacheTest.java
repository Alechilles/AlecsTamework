package com.alechilles.alecstamework.companion.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.item.AdmissionCache.Cached;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AdmissionCacheTest {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private final AtomicLong now = new AtomicLong(1_000L);
    private final AdmissionCache cache = new AdmissionCache(now::get);

    @Test
    void decisionExpiresAfterTtl() {
        cache.put(ALICE, "wolf", false);
        now.addAndGet(AdmissionCache.TTL_MS - 1);
        assertEquals(Cached.DENY, cache.get(ALICE, "wolf"));
        now.addAndGet(1);
        assertEquals(Cached.MISS, cache.get(ALICE, "wolf"));
    }

    @Test
    void invalidatePlayerDropsOnlyThatPlayer() {
        cache.put(ALICE, "wolf", true);
        cache.put(BOB, "wolf", true);
        cache.invalidatePlayer(ALICE);
        assertEquals(Cached.MISS, cache.get(ALICE, "wolf"));
        assertEquals(Cached.ALLOW, cache.get(BOB, "wolf"));
    }

    @Test
    void noticeIsDueOncePerInterval() {
        UUID profile = UUID.randomUUID();
        assertTrue(cache.noticeDue(ALICE, profile));
        now.addAndGet(AdmissionCache.NOTICE_EVERY_MS - 1);
        assertFalse(cache.noticeDue(ALICE, profile));
        now.addAndGet(1);
        assertTrue(cache.noticeDue(ALICE, profile));
    }

    @Test
    void ownerChangeDropsBothOwnersButSummaryChangeKeepsThem() {
        CompanionRecord owned = CompanionRecord.builder(UUID.randomUUID(), "wolf", CompanionLocation.item())
                .ownerUuid(ALICE).build();
        cache.put(ALICE, "wolf", true);
        cache.put(BOB, "wolf", false);

        cache.onRecordChanged(owned, owned.toBuilder().displayName("Rex").build());
        assertEquals(Cached.ALLOW, cache.get(ALICE, "wolf"));

        cache.onRecordChanged(owned, owned.toBuilder().ownerUuid(BOB).build());
        assertEquals(Cached.MISS, cache.get(ALICE, "wolf"));
        assertEquals(Cached.MISS, cache.get(BOB, "wolf"));
    }
}
