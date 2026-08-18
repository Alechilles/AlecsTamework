package com.alechilles.alecstamework.npc.progression;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Stores the next needs-update time for each companion in a generation-safe due queue.
 *
 * <p>Refreshing an entry leaves its old heap entry in place. The generation check removes that
 * stale entry when it reaches the head of the queue.
 */
final class CompanionNeedsSchedule {
    private final PriorityQueue<Entry> due = new PriorityQueue<>();
    private final Map<UUID, State> states = new HashMap<>();
    private long generationSequence;

    void register(UUID npcId, long nowMs, long initialDelayMs) {
        Objects.requireNonNull(npcId, "npcId");
        long generation = nextGeneration();
        long dueAtMs = safeAdd(nowMs, Math.max(0L, initialDelayMs));
        states.put(npcId, new State(generation, dueAtMs));
        due.add(new Entry(npcId, generation, dueAtMs));
    }

    void remove(UUID npcId) {
        Objects.requireNonNull(npcId, "npcId");
        states.remove(npcId);
    }

    UUID pollDue(long nowMs) {
        discardStale();
        Entry entry = due.peek();
        if (entry == null || entry.dueAtMs() > nowMs) {
            return null;
        }
        due.poll();
        states.remove(entry.npcId());
        return entry.npcId();
    }

    void reschedule(UUID npcId, long dueAtMs) {
        Objects.requireNonNull(npcId, "npcId");
        long generation = nextGeneration();
        states.put(npcId, new State(generation, dueAtMs));
        due.add(new Entry(npcId, generation, dueAtMs));
    }

    long nextDueAtMs() {
        discardStale();
        Entry entry = due.peek();
        return entry == null ? 0L : entry.dueAtMs();
    }

    int size() {
        return states.size();
    }

    private long nextGeneration() {
        return ++generationSequence;
    }

    private void discardStale() {
        while (true) {
            Entry entry = due.peek();
            if (entry == null || isCurrent(entry)) {
                return;
            }
            due.poll();
        }
    }

    private boolean isCurrent(Entry entry) {
        State state = states.get(entry.npcId());
        return state != null
                && state.generation() == entry.generation()
                && state.dueAtMs() == entry.dueAtMs();
    }

    private static long safeAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private record Entry(UUID npcId, long generation, long dueAtMs) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            int dueComparison = Long.compare(dueAtMs, other.dueAtMs);
            if (dueComparison != 0) {
                return dueComparison;
            }
            return npcId.compareTo(other.npcId);
        }
    }

    private record State(long generation, long dueAtMs) {
    }
}
