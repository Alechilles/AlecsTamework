package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Tracks owner online/offline state transitions and resolves effective elapsed windows for needs progression.
 */
public final class OwnerPresenceTimelineService {
    private static final OwnerPresenceTimelineService INSTANCE = new OwnerPresenceTimelineService();
    private static final double HOURS_TO_MILLIS = 3_600_000.0;
    private static final double DEFAULT_GRACE_HOURS = 72.0;
    private static final double DEFAULT_OFFLINE_MULTIPLIER = 1.0;
    /** Marks a transition that has not happened. Zero and negative session instants are real values. */
    private static final long UNSET_MS = Long.MIN_VALUE;
    private final Map<UUID, PresenceState> presenceByOwner = new ConcurrentHashMap<>();

    private OwnerPresenceTimelineService() {
    }

    public static OwnerPresenceTimelineService get() {
        return INSTANCE;
    }

    public void seedOnlinePlayersFromUniverse() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        Collection<PlayerRef> players = universe.getPlayers();
        if (players == null || players.isEmpty()) {
            return;
        }
        long nowMs = CompanionRuntimeClock.nowMs();
        for (PlayerRef playerRef : players) {
            if (playerRef == null || playerRef.getUuid() == null) {
                continue;
            }
            markOnline(playerRef.getUuid(), nowMs);
            SharedPresenceService.get().onPlayerOnline(playerRef.getUuid());
        }
    }

    /** Marks a derived presence id online. Player ids arrive through the connect events instead. */
    void markPresenceOnline(UUID presenceId) {
        markOnline(presenceId, CompanionRuntimeClock.nowMs());
    }

    /** Marks a derived presence id offline. */
    void markPresenceOffline(UUID presenceId) {
        markOffline(presenceId, CompanionRuntimeClock.nowMs());
    }

    public void onPlayerConnect(@Nullable PlayerConnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }
        UUID ownerId = event.getPlayerRef().getUuid();
        if (ownerId == null) {
            return;
        }
        markOnline(ownerId, CompanionRuntimeClock.nowMs());
        SharedPresenceService.get().onPlayerOnline(ownerId);
    }

    public void onPlayerDisconnect(@Nullable PlayerDisconnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }
        UUID ownerId = event.getPlayerRef().getUuid();
        if (ownerId == null) {
            return;
        }
        markOffline(ownerId, CompanionRuntimeClock.nowMs());
        SharedPresenceService.get().onPlayerOffline(ownerId);
    }

    public long resolveEffectiveElapsedMs(@Nullable UUID ownerId,
                                          long windowStartMs,
                                          long windowEndMs,
                                          @Nullable TwNeedsConfig.TickPolicySettings tickPolicy) {
        if (windowEndMs <= windowStartMs) {
            return 0L;
        }
        long totalWindowMs = windowEndMs - windowStartMs;
        TwNeedsConfig.TickPolicyMode mode = tickPolicy != null
                ? tickPolicy.getMode()
                : TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY;
        if (mode == TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER || ownerId == null) {
            return totalWindowMs;
        }

        PresenceState state = resolveOwnerState(ownerId, windowEndMs);
        if (state == null) {
            return totalWindowMs;
        }
        double graceHours = tickPolicy != null ? tickPolicy.getOwnerOfflineGraceHours() : DEFAULT_GRACE_HOURS;
        double offlineMultiplier = tickPolicy != null
                ? tickPolicy.getOwnerOfflineDecayMultiplier()
                : DEFAULT_OFFLINE_MULTIPLIER;
        long graceMs = resolveGraceDurationMs(graceHours);

        if (state.online) {
            long connectedAtMs = state.lastConnectedAtMs != UNSET_MS ? state.lastConnectedAtMs : state.lastStatusChangeMs;
            if (connectedAtMs <= windowStartMs) {
                return totalWindowMs;
            }
            long offlineSegmentEndMs = Math.min(windowEndMs, connectedAtMs);
            long onlineWindowMs = Math.max(0L, windowEndMs - Math.max(windowStartMs, connectedAtMs));
            long offlineEffectiveMs = resolveOfflineEffectiveElapsedMs(
                    state,
                    windowStartMs,
                    offlineSegmentEndMs,
                    graceMs,
                    offlineMultiplier
            );
            return clampToWindow(onlineWindowMs + offlineEffectiveMs, totalWindowMs);
        }

        long disconnectedAtMs = state.lastDisconnectedAtMs != UNSET_MS ? state.lastDisconnectedAtMs : state.lastStatusChangeMs;
        if (disconnectedAtMs <= windowStartMs) {
            return resolveOfflineEffectiveElapsedMs(
                    state,
                    windowStartMs,
                    windowEndMs,
                    graceMs,
                    offlineMultiplier
            );
        }
        long onlineSegmentEndMs = Math.min(windowEndMs, disconnectedAtMs);
        long onlineWindowMs = Math.max(0L, onlineSegmentEndMs - windowStartMs);
        long offlineEffectiveMs = resolveOfflineEffectiveElapsedMs(
                state,
                onlineSegmentEndMs,
                windowEndMs,
                graceMs,
                offlineMultiplier
        );
        return clampToWindow(onlineWindowMs + offlineEffectiveMs, totalWindowMs);
    }

    /**
     * Debug fast-forward: how much of {@code advanceMs} counts if it passed from {@code nowMs} on,
     * with the owner's presence as it is now (online counts all of it; offline counts only what
     * falls after the offline grace). The window lies ahead of now, so presence history before now
     * cannot clip it. An owner not seen yet is first recorded at {@code nowMs}, never at a future
     * instant.
     */
    long resolveAdvanceElapsedMs(@Nullable UUID ownerId,
                                 long nowMs,
                                 long advanceMs,
                                 @Nullable TwNeedsConfig.TickPolicySettings tickPolicy) {
        if (advanceMs <= 0L) {
            return 0L;
        }
        if (ownerId != null) {
            resolveOwnerState(ownerId, nowMs);
        }
        return resolveEffectiveElapsedMs(ownerId, nowMs, saturatingAdd(nowMs, advanceMs), tickPolicy);
    }

    /** Offline time of one presence id in both owner clocks after a debug offline-age. */
    public record OfflineAge(long presenceOfflineMs, long progressionOfflineMs) {
    }

    /**
     * Debug hook: moves an offline presence id's offline start {@code ms} earlier in both owner clocks,
     * this timeline (needs, memory only) and {@link AnimalProgressionClock} (growth, aging, production;
     * checkpointed to disk), so the offline grace runs out sooner. Each clock changes under its own
     * lock; time either clock already counted stays as it was. An id not seen yet is seeded first, as
     * a normal read does.
     *
     * @return the offline time afterwards, or null when the id is online in either clock or the
     *         progression clock is stopped (nothing changes then)
     */
    @Nullable
    public OfflineAge ageOfflineStartForDebug(UUID ownerId, long ms) {
        long nowMs = CompanionRuntimeClock.nowMs();
        // Seed before the progression clock moves: a new entry here copies its saved offline start.
        PresenceState state = resolveOwnerState(ownerId, nowMs);
        if (state == null || state.online || AnimalProgressionClock.get().offlineStartedAtMs(ownerId) == null) {
            return null;
        }
        Long progressionMs = AnimalProgressionClock.get().ageOfflineStartForDebug(ownerId, ms);
        if (progressionMs == null) {
            return null;
        }
        PresenceState aged = presenceByOwner.computeIfPresent(ownerId,
                (uuid, existing) -> existing.online ? existing : existing.offlineStartMovedBack(ms));
        if (aged == null || aged.online) {
            // The owner connected in between; the connect settled the progression clock as usual.
            return null;
        }
        return new OfflineAge(nowMs - aged.lastDisconnectedAtMs, progressionMs);
    }

    /** Applies the current runtime owner policy to one loaded world-time tick. */
    long resolveWorldTimeElapsedMs(@Nullable UUID ownerId,
                                   long totalWindowMs,
                                   @Nullable TwNeedsConfig.TickPolicySettings tickPolicy) {
        if (totalWindowMs <= 0L) {
            return 0L;
        }
        TwNeedsConfig.TickPolicyMode mode = tickPolicy != null
                ? tickPolicy.getMode()
                : TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY;
        if (mode == TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER || ownerId == null) {
            return totalWindowMs;
        }

        long nowMs = CompanionRuntimeClock.nowMs();
        PresenceState state = resolveOwnerState(ownerId, nowMs);
        if (state == null || state.online) {
            return totalWindowMs;
        }
        double graceHours = tickPolicy != null ? tickPolicy.getOwnerOfflineGraceHours() : DEFAULT_GRACE_HOURS;
        long graceEndMs = saturatingAdd(state.lastStatusChangeMs, resolveGraceDurationMs(graceHours));
        if (nowMs < graceEndMs) {
            return 0L;
        }
        double multiplier = tickPolicy != null
                ? tickPolicy.getOwnerOfflineDecayMultiplier()
                : DEFAULT_OFFLINE_MULTIPLIER;
        return clampToWindow(scaleDurationMs(totalWindowMs, multiplier), totalWindowMs);
    }

    void clearForTests() {
        presenceByOwner.clear();
        CompanionRuntimeClock.resetForTests();
    }

    void markOnlineForTests(UUID ownerId, long atMs) {
        markOnline(ownerId, atMs);
    }

    void markOfflineForTests(UUID ownerId, long atMs) {
        markOffline(ownerId, atMs);
    }

    private long resolveOfflineEffectiveElapsedMs(PresenceState state,
                                                  long segmentStartMs,
                                                  long segmentEndMs,
                                                  long graceMs,
                                                  double offlineMultiplier) {
        if (segmentEndMs <= segmentStartMs || offlineMultiplier <= 0.0) {
            return 0L;
        }
        if (graceMs <= 0L) {
            return scaleDurationMs(segmentEndMs - segmentStartMs, offlineMultiplier);
        }
        long offlineStartMs = state.lastDisconnectedAtMs != UNSET_MS ? state.lastDisconnectedAtMs : state.lastStatusChangeMs;
        long graceEndMs = saturatingAdd(offlineStartMs, graceMs);
        if (segmentEndMs <= graceEndMs) {
            return 0L;
        }
        long effectiveStartMs = Math.max(segmentStartMs, graceEndMs);
        return scaleDurationMs(segmentEndMs - effectiveStartMs, offlineMultiplier);
    }

    private PresenceState resolveOwnerState(UUID ownerId, long atMs) {
        PresenceState existing = presenceByOwner.get(ownerId);
        if (existing != null) {
            return existing;
        }
        boolean online = isOwnerOnlineInUniverse(ownerId);
        long sinceMs = atMs;
        if (!online) {
            // Presence is memory-only, so a new server session would otherwise restart the offline
            // grace. The progression clock saves the offline time each owner has already used.
            Long savedOfflineStartMs = AnimalProgressionClock.get().offlineStartedAtMs(ownerId);
            if (savedOfflineStartMs != null) {
                sinceMs = Math.min(atMs, savedOfflineStartMs);
            }
        }
        PresenceState seeded = PresenceState.initial(online, sinceMs);
        PresenceState prior = presenceByOwner.putIfAbsent(ownerId, seeded);
        return prior == null ? seeded : prior;
    }

    private void markOnline(UUID ownerId, long atMs) {
        if (ownerId == null) {
            return;
        }
        AnimalProgressionClock.get().onOwnerConnected(ownerId);
        presenceByOwner.compute(ownerId, (uuid, existing) -> existing == null
                ? PresenceState.initial(true, atMs)
                : existing.transitionTo(true, atMs));
    }

    private void markOffline(UUID ownerId, long atMs) {
        if (ownerId == null) {
            return;
        }
        AnimalProgressionClock.get().onOwnerDisconnected(ownerId);
        presenceByOwner.compute(ownerId, (uuid, existing) -> existing == null
                ? PresenceState.initial(false, atMs)
                : existing.transitionTo(false, atMs));
    }

    private static boolean isOwnerOnlineInUniverse(UUID ownerId) {
        if (ownerId == null) {
            return false;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return false;
        }
        return universe.getPlayer(ownerId) != null;
    }

    private static long resolveGraceDurationMs(double graceHours) {
        if (!Double.isFinite(graceHours) || graceHours <= 0.0) {
            return 0L;
        }
        double graceMs = graceHours * HOURS_TO_MILLIS;
        if (!Double.isFinite(graceMs)) {
            return Long.MAX_VALUE;
        }
        return graceMs >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(graceMs);
    }

    private static long scaleDurationMs(long durationMs, double multiplier) {
        if (durationMs <= 0L || !Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 0L;
        }
        double scaled = durationMs * multiplier;
        if (!Double.isFinite(scaled) || scaled <= 0.0) {
            return 0L;
        }
        return scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(scaled);
    }

    private static long clampToWindow(long value, long totalWindowMs) {
        if (value <= 0L) {
            return 0L;
        }
        if (value >= totalWindowMs) {
            return totalWindowMs;
        }
        return value;
    }

    private static long saturatingAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class PresenceState {
        private final boolean online;
        private final long lastStatusChangeMs;
        private final long lastConnectedAtMs;
        private final long lastDisconnectedAtMs;

        private PresenceState(boolean online, long lastStatusChangeMs, long lastConnectedAtMs, long lastDisconnectedAtMs) {
            this.online = online;
            this.lastStatusChangeMs = lastStatusChangeMs;
            this.lastConnectedAtMs = lastConnectedAtMs;
            this.lastDisconnectedAtMs = lastDisconnectedAtMs;
        }

        private static PresenceState initial(boolean online, long atMs) {
            long connectedAtMs = online ? atMs : UNSET_MS;
            long disconnectedAtMs = online ? UNSET_MS : atMs;
            return new PresenceState(online, atMs, connectedAtMs, disconnectedAtMs);
        }

        private PresenceState transitionTo(boolean nextOnline, long atMs) {
            if (online == nextOnline) {
                return this;
            }
            if (nextOnline) {
                return new PresenceState(true, atMs, atMs, lastDisconnectedAtMs);
            }
            return new PresenceState(false, atMs, lastConnectedAtMs, atMs);
        }

        /** An offline state whose disconnect happened {@code ms} earlier. */
        private PresenceState offlineStartMovedBack(long ms) {
            long disconnectedAtMs = lastDisconnectedAtMs != UNSET_MS ? lastDisconnectedAtMs : lastStatusChangeMs;
            long movedMs = disconnectedAtMs < Long.MIN_VALUE + 1L + ms ? Long.MIN_VALUE + 1L : disconnectedAtMs - ms;
            return new PresenceState(false, movedMs, lastConnectedAtMs, movedMs);
        }
    }
}
