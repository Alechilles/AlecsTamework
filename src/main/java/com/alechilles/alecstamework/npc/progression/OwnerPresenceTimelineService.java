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
        long nowMs = System.currentTimeMillis();
        for (PlayerRef playerRef : players) {
            if (playerRef == null || playerRef.getUuid() == null) {
                continue;
            }
            markOnline(playerRef.getUuid(), nowMs);
        }
    }

    public void onPlayerConnect(@Nullable PlayerConnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }
        UUID ownerId = event.getPlayerRef().getUuid();
        if (ownerId == null) {
            return;
        }
        markOnline(ownerId, System.currentTimeMillis());
    }

    public void onPlayerDisconnect(@Nullable PlayerDisconnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }
        UUID ownerId = event.getPlayerRef().getUuid();
        if (ownerId == null) {
            return;
        }
        markOffline(ownerId, System.currentTimeMillis());
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
            long connectedAtMs = state.lastConnectedAtMs > 0L ? state.lastConnectedAtMs : state.lastStatusChangeMs;
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

        long disconnectedAtMs = state.lastDisconnectedAtMs > 0L ? state.lastDisconnectedAtMs : state.lastStatusChangeMs;
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

    void clearForTests() {
        presenceByOwner.clear();
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
        long offlineStartMs = state.lastDisconnectedAtMs > 0L ? state.lastDisconnectedAtMs : state.lastStatusChangeMs;
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
        PresenceState seeded = PresenceState.initial(isOwnerOnlineInUniverse(ownerId), atMs);
        PresenceState prior = presenceByOwner.putIfAbsent(ownerId, seeded);
        return prior == null ? seeded : prior;
    }

    private void markOnline(UUID ownerId, long atMs) {
        if (ownerId == null) {
            return;
        }
        presenceByOwner.compute(ownerId, (uuid, existing) -> existing == null
                ? PresenceState.initial(true, atMs)
                : existing.transitionTo(true, atMs));
    }

    private void markOffline(UUID ownerId, long atMs) {
        if (ownerId == null) {
            return;
        }
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
            long connectedAtMs = online ? atMs : 0L;
            long disconnectedAtMs = online ? 0L : atMs;
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
    }
}
