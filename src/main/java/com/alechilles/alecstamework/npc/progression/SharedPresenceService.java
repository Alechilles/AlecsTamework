package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Decides whose presence drives a shared companion's needs, aging and production.
 *
 * <p>An unshared or private companion follows its owner alone. A shared companion follows its
 * owner or anyone the owner shares with: while any of them is online it gets hungry and keeps
 * producing. To express that without a second kind of clock, each sharing owner gets a derived
 * presence id. {@link OwnerPresenceTimelineService} and {@link AnimalProgressionClock} track it
 * like a player id that is "online" while the owner or a sharer is.</p>
 *
 * <p><b>Periodic scan.</b> Scope: owners with at least one grant. Cadence: every
 * {@value #SWEEP_SECONDS} seconds, and once after each player connect, disconnect or grant
 * change. Bound: per owner, the named grants (at most
 * {@link SharedAccessService#MAX_PLAYER_GRANTS_PER_OWNER}) plus, only when a group is shared, one
 * in-memory membership lookup per online player. It runs on its own daemon thread and touches no
 * entity, world or player object: only ids and immutable grants. Events alone are not enough
 * because group providers do not report membership changes.</p>
 */
public final class SharedPresenceService implements AutoCloseable {
    private static final long SWEEP_SECONDS = 10L;
    private static final SharedPresenceService INSTANCE = new SharedPresenceService();

    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    /** Sharing owners being followed, with whether their derived id is currently online. */
    private final Map<UUID, Boolean> sharedOnlineByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sharedIdByOwner = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    /** True while a requested sweep waits to run, so a burst of logins queues one sweep, not one each. */
    private final java.util.concurrent.atomic.AtomicBoolean sweepQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    @Nullable private ScheduledExecutorService sweeper;
    private boolean listening;

    private SharedPresenceService() {
    }

    @Nonnull
    public static SharedPresenceService get() {
        return INSTANCE;
    }

    /** Starts following sharing owners. Call after {@link SharedAccessService#start}. */
    public void start() {
        synchronized (lifecycleLock) {
            if (!listening) {
                SharedAccessService.get().addChangeListener(this::refresh);
                listening = true;
            }
            if (sweeper == null) {
                sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "tamework-shared-presence");
                    thread.setDaemon(true);
                    return thread;
                });
                sweeper.scheduleWithFixedDelay(this::sweepQuietly, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
            }
        }
        sweep();
    }

    @Override
    public void close() {
        ScheduledExecutorService executor;
        synchronized (lifecycleLock) {
            executor = sweeper;
            sweeper = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        onlinePlayers.clear();
        sharedOnlineByOwner.clear();
    }

    void onPlayerOnline(@Nonnull UUID playerId) {
        if (onlinePlayers.add(playerId)) {
            requestSweep();
        }
    }

    void onPlayerOffline(@Nonnull UUID playerId) {
        if (onlinePlayers.remove(playerId)) {
            requestSweep();
        }
    }

    /**
     * The id whose presence drives a companion: the owner, or the owner's derived shared id when
     * the owner shares and the companion is not private. Null for an unowned companion.
     */
    @Nullable
    public UUID presenceIdFor(@Nullable UUID ownerId, @Nullable UUID profileId) {
        if (ownerId == null) {
            return null;
        }
        SharedAccessService access = SharedAccessService.get();
        if (!access.sharesAnything(ownerId) || access.isPrivate(ownerId, profileId)) {
            return ownerId;
        }
        return sharedIdFor(ownerId);
    }

    /** True when {@code presenceId} is {@code ownerId} itself or that owner's shared presence id. */
    boolean isPresenceIdOf(@Nullable UUID ownerId, @Nullable String presenceId) {
        return ownerId != null && presenceId != null
                && (presenceId.equals(ownerId.toString()) || presenceId.equals(sharedIdFor(ownerId).toString()));
    }

    /** Recomputes every sharing owner, and stops following owners who no longer share. */
    void sweep() {
        Set<UUID> sharing = SharedAccessService.get().ownersWithGrants();
        sharing.forEach(this::refresh);
        for (UUID ownerId : sharedOnlineByOwner.keySet()) {
            if (!sharing.contains(ownerId)) {
                refresh(ownerId);
            }
        }
    }

    void clearForTests() {
        close();
    }

    private void refresh(@Nonnull UUID ownerId) {
        SharedAccessService access = SharedAccessService.get();
        if (!access.sharesAnything(ownerId)) {
            sharedOnlineByOwner.computeIfPresent(ownerId, (id, wasOnline) -> {
                if (wasOnline) {
                    OwnerPresenceTimelineService.get().markPresenceOffline(sharedIdFor(id));
                }
                return null;
            });
            return;
        }
        boolean online = onlinePlayers.contains(ownerId) || anySharerOnline(ownerId, access);
        sharedOnlineByOwner.compute(ownerId, (id, wasOnline) -> {
            // A first sighting that is offline marks nothing: the timeline then seeds the id from
            // the offline time the progression clock saved, instead of restarting its grace.
            if (wasOnline == null ? online : wasOnline != online) {
                if (online) {
                    OwnerPresenceTimelineService.get().markPresenceOnline(sharedIdFor(id));
                } else {
                    OwnerPresenceTimelineService.get().markPresenceOffline(sharedIdFor(id));
                }
            }
            return online;
        });
    }

    private boolean anySharerOnline(@Nonnull UUID ownerId, @Nonnull SharedAccessService access) {
        OwnerGrants grants = access.grants(ownerId);
        for (UUID playerId : grants.players().keySet()) {
            if (onlinePlayers.contains(playerId)) {
                return true;
            }
        }
        if (grants.groups().isEmpty()) {
            return false;
        }
        for (UUID playerId : onlinePlayers) {
            if (playerId.equals(ownerId)) {
                continue;
            }
            for (String groupId : grants.groups().keySet()) {
                if (access.isMember(groupId, ownerId, playerId)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nonnull
    private UUID sharedIdFor(@Nonnull UUID ownerId) {
        return sharedIdByOwner.computeIfAbsent(ownerId, id -> UUID.nameUUIDFromBytes(
                ("tamework-shared-presence:" + id).getBytes(StandardCharsets.UTF_8)));
    }

    private void requestSweep() {
        ScheduledExecutorService executor;
        synchronized (lifecycleLock) {
            executor = sweeper;
        }
        if (executor == null) {
            sweep();
            return;
        }
        if (!sweepQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                sweepQueued.set(false);
                sweepQuietly();
            });
        } catch (RuntimeException rejected) {
            // Shutting down.
            sweepQueued.set(false);
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException failed) {
            // A failed sweep must not cancel the schedule; the next one retries.
        }
    }
}
