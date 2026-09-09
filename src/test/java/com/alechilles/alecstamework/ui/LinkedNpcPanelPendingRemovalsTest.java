package com.alechilles.alecstamework.ui;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkedNpcPanelPendingRemovalsTest {
    @Test void staleRefreshesHideOnlyClickedAnimalAndRestoreUnconfirmedRemoval() {
        AtomicLong clock = new AtomicLong();
        var pending = new LinkedNpcPanelPendingRemovals(clock::get);
        var removed = entry();
        var other = entry();
        var source = new LinkedNpcEntry[]{removed, other};
        pending.hide(removed.npcUuid());
        assertArrayEquals(new LinkedNpcEntry[]{other}, pending.filter(source));
        clock.set(TimeUnit.SECONDS.toNanos(4));
        assertArrayEquals(new LinkedNpcEntry[]{other}, pending.filter(source));
        pending.hide(removed.npcUuid()); // Duplicate clicks do not extend the grace period.
        assertEquals(1000, pending.remainingMillis());
        clock.set(TimeUnit.SECONDS.toNanos(5));
        assertArrayEquals(source, pending.filter(source));
        assertEquals(Long.MAX_VALUE, pending.remainingMillis());
    }

    @Test void successfulRemovalIsNotReinsertedWhenGraceExpires() {
        AtomicLong clock = new AtomicLong();
        var pending = new LinkedNpcPanelPendingRemovals(clock::get);
        var removed = entry();
        pending.hide(removed.npcUuid());
        assertEquals(0, pending.filter(new LinkedNpcEntry[]{removed}).length);
        clock.set(TimeUnit.SECONDS.toNanos(6));
        assertEquals(0, pending.filter(new LinkedNpcEntry[0]).length);
    }

    private static LinkedNpcEntry entry() {
        return new LinkedNpcEntry(UUID.randomUUID(), "Cow", 0, 0, 0, 0, 0, null,
                0, 0, 0, 0, false, false, false, false, false, false, 0L,
                null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, false, true,
                "Cow", "Cow", null, null, null, false, false, 0L, 0.0, false);
    }
}
