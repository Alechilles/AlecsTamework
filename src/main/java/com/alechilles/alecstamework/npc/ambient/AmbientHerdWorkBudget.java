package com.alechilles.alecstamework.npc.ambient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Process-wide scalar caps for ambient-herd work. Never call into a world while holding its lock.
 */
public final class AmbientHerdWorkBudget {
    public enum Work {
        POINT_READ,
        LOADED_REFERENCE_LOOKUP,
        MOVEMENT_PROBE,
        ASTAR_EXPANSION,
        ASTAR_INITIALIZATION
    }

    static final long WINDOW_MS = 50L;
    private static final int WORLD_ACTIVITIES = 4, PROCESS_ACTIVITIES = 8;
    private static final int WORLD_PARTICIPANTS = 64, PROCESS_PARTICIPANTS = 128;
    private static final int WORLD_REGISTERED = 128, PROCESS_REGISTERED = 512;
    private static final int WORLD_CACHED = 64, PROCESS_CACHED = 256;
    private long epoch = Long.MIN_VALUE;
    private final Map<UUID, Usage> worlds = new HashMap<>();
    private final Usage process = new Usage();
    private final Map<UUID, Set<UUID>> activities = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> activityParticipants = new HashMap<>();
    private final Map<UUID, Set<UUID>> registered = new HashMap<>();
    private final Map<UUID, Set<String>> cached = new HashMap<>();

    public synchronized boolean claim(UUID worldId, Work work, int units, long monotonicMillis) {
        if (worldId == null || work == null || units <= 0) return false;
        rollEpoch(monotonicMillis);
        int worldLimit = worldLimit(work), processLimit = processLimit(work);
        Usage world = worlds.computeIfAbsent(worldId, ignored -> new Usage());
        int worldUsed = world.work[work.ordinal()], processUsed = process.work[work.ordinal()];
        if (units > worldLimit - worldUsed || units > processLimit - processUsed) return false;
        world.work[work.ordinal()] += units;
        process.work[work.ordinal()] += units;
        return true;
    }

    public synchronized boolean tryAdmitActivity(UUID worldId, UUID activityId, int participants) {
        if (worldId == null || activityId == null || participants < 2 || participants > 16)
            return false;
        Set<UUID> worldActivities = activities.computeIfAbsent(worldId, ignored -> new HashSet<>());
        if (worldActivities.contains(activityId)) return true;
        Usage world = worlds.computeIfAbsent(worldId, ignored -> new Usage());
        if (worldActivities.size() >= WORLD_ACTIVITIES
                || process.activities >= PROCESS_ACTIVITIES
                || world.participants + participants > WORLD_PARTICIPANTS
                || process.participants + participants > PROCESS_PARTICIPANTS) return false;
        worldActivities.add(activityId);
        activityParticipants
                .computeIfAbsent(worldId, ignored -> new HashMap<>())
                .put(activityId, participants);
        world.activities++;
        world.participants += participants;
        process.activities++;
        process.participants += participants;
        return true;
    }

    public synchronized void releaseActivity(UUID worldId, UUID activityId) {
        Map<UUID, Integer> participants = activityParticipants.get(worldId);
        Set<UUID> worldActivities = activities.get(worldId);
        if (participants == null || worldActivities == null || !worldActivities.remove(activityId))
            return;
        int count = participants.getOrDefault(activityId, 0);
        participants.remove(activityId);
        Usage world = worlds.get(worldId);
        if (world != null) {
            world.activities = Math.max(0, world.activities - 1);
            world.participants = Math.max(0, world.participants - count);
        }
        process.activities = Math.max(0, process.activities - 1);
        process.participants = Math.max(0, process.participants - count);
    }

    public synchronized boolean tryRegister(UUID worldId, UUID leaderId) {
        if (worldId == null || leaderId == null) return false;
        Set<UUID> values = registered.computeIfAbsent(worldId, ignored -> new HashSet<>());
        if (values.contains(leaderId)) return true;
        if (values.size() >= WORLD_REGISTERED || process.registered >= PROCESS_REGISTERED)
            return false;
        values.add(leaderId);
        worlds.computeIfAbsent(worldId, ignored -> new Usage()).registered++;
        process.registered++;
        return true;
    }

    public synchronized void releaseRegistered(UUID worldId, UUID leaderId) {
        Set<UUID> values = registered.get(worldId);
        if (values == null || !values.remove(leaderId)) return;
        Usage world = worlds.get(worldId);
        if (world != null) world.registered = Math.max(0, world.registered - 1);
        process.registered = Math.max(0, process.registered - 1);
    }

    public synchronized boolean tryCache(UUID worldId, String key) {
        if (worldId == null || key == null || key.isBlank()) return false;
        Set<String> values = cached.computeIfAbsent(worldId, ignored -> new HashSet<>());
        if (values.contains(key)) return true;
        if (values.size() >= WORLD_CACHED || process.cached >= PROCESS_CACHED) return false;
        values.add(key);
        worlds.computeIfAbsent(worldId, ignored -> new Usage()).cached++;
        process.cached++;
        return true;
    }

    public synchronized void releaseCached(UUID worldId, String key) {
        Set<String> values = cached.get(worldId);
        if (values == null || !values.remove(key)) return;
        Usage world = worlds.get(worldId);
        if (world != null) world.cached = Math.max(0, world.cached - 1);
        process.cached = Math.max(0, process.cached - 1);
    }

    public synchronized void removeWorld(UUID worldId) {
        if (worldId == null) return;
        Map<UUID, Integer> activity = activityParticipants.get(worldId);
        if (activity != null)
            for (UUID id : Set.copyOf(activity.keySet())) releaseActivity(worldId, id);
        Set<UUID> leaders = registered.get(worldId);
        if (leaders != null) for (UUID id : Set.copyOf(leaders)) releaseRegistered(worldId, id);
        Set<String> keys = cached.get(worldId);
        if (keys != null) for (String key : Set.copyOf(keys)) releaseCached(worldId, key);
        worlds.remove(worldId);
        activities.remove(worldId);
        activityParticipants.remove(worldId);
        registered.remove(worldId);
        cached.remove(worldId);
    }

    private void rollEpoch(long now) {
        long next = now - Math.floorMod(now, WINDOW_MS);
        if (epoch == Long.MIN_VALUE || next > epoch) {
            epoch = next;
            for (Usage usage : worlds.values()) usage.clearWork();
            process.clearWork();
        }
    }

    private static int worldLimit(Work work) {
        return switch (work) {
            case POINT_READ, LOADED_REFERENCE_LOOKUP, ASTAR_EXPANSION -> 32;
            case MOVEMENT_PROBE -> 8;
            case ASTAR_INITIALIZATION -> 1;
        };
    }

    private static int processLimit(Work work) {
        return switch (work) {
            case POINT_READ, LOADED_REFERENCE_LOOKUP, ASTAR_EXPANSION -> 128;
            case MOVEMENT_PROBE -> 32;
            case ASTAR_INITIALIZATION -> 2;
        };
    }

    private static final class Usage {
        private final int[] work = new int[Work.values().length];
        private int activities, participants, registered, cached;

        private void clearWork() {
            java.util.Arrays.fill(work, 0);
        }
    }
}
