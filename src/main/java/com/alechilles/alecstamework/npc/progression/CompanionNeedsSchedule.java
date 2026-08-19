package com.alechilles.alecstamework.npc.progression;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Stores the next needs-update time for each companion in an indexed ordered due queue.
 *
 * <p>Each UUID has one entry in both indexes. Refresh and removal update the ordered set at the
 * same time, so an idle head check never performs lazy stale-entry cleanup.
 */
final class CompanionNeedsSchedule {
    private final TreeSet<Entry> due = new TreeSet<>();
    private final Map<UUID, Entry> entriesByNpc = new HashMap<>();
    private Entry firstDue;

    void register(UUID npcId, long nowMs, long initialDelayMs) {
        Objects.requireNonNull(npcId, "npcId");
        long dueAtMs = safeAdd(nowMs, Math.max(0L, initialDelayMs));
        replace(npcId, dueAtMs);
    }

    void remove(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId");
        Entry existing = entriesByNpc.remove(npcId);
        if (existing == null) {
            return;
        }
        due.remove(existing);
        if (existing == firstDue) {
            firstDue = due.isEmpty() ? null : due.first();
        }
    }

    UUID pollDue(long nowMs) {
        Entry entry = firstDue;
        if (entry == null || entry.dueAtMs() > nowMs) {
            return null;
        }
        due.remove(entry);
        entriesByNpc.remove(entry.npcId());
        firstDue = due.isEmpty() ? null : due.first();
        return entry.npcId();
    }

    void reschedule(UUID npcId, long dueAtMs) {
        Objects.requireNonNull(npcId, "npcId");
        replace(npcId, dueAtMs);
    }

    long nextDueAtMs() {
        return firstDue == null ? 0L : firstDue.dueAtMs();
    }

    int size() {
        return entriesByNpc.size();
    }

    private void replace(UUID npcId, long dueAtMs) {
        Entry existing = entriesByNpc.remove(npcId);
        if (existing != null) {
            due.remove(existing);
        }
        Entry replacement = new Entry(npcId, dueAtMs);
        entriesByNpc.put(npcId, replacement);
        due.add(replacement);
        if (existing == firstDue || firstDue == null || replacement.compareTo(firstDue) < 0) {
            firstDue = due.first();
        }
    }

    private static long safeAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private record Entry(UUID npcId, long dueAtMs) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            int dueComparison = Long.compare(dueAtMs, other.dueAtMs);
            if (dueComparison != 0) {
                return dueComparison;
            }
            return npcId.compareTo(other.npcId);
        }
    }
}
