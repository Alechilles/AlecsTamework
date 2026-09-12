package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Per-store slot allocation for active native-flock formation motions.
 *
 * <p>Only motion callbacks claim and report members, so idle flock members never reserve a slot.
 * State contains stable IDs and coordinate snapshots only; it deliberately has no ECS writes or
 * scheduled work. Inactive layouts expire during later active callbacks.
 */
public final class NativeFormationSlots {
    private static final NativeFormationSlots INSTANCE = new NativeFormationSlots();
    private static final long GROUP_PRUNE_CADENCE_MILLIS = 5_000L;
    private static final long GROUP_EXPIRY_MILLIS = 10_000L;
    private static final int GROUP_PRUNE_BATCH = 16;

    private final StoreScopedState<State> states = new StoreScopedState<>(State::new);

    private NativeFormationSlots() {
    }

    @Nonnull
    public static NativeFormationSlots get() {
        return INSTANCE;
    }

    /**
     * Resolves a layout key from the live components currently owned by this motion callback.
     * Missing identity is a test-fixture/legacy fallback signal, not a separate runtime layout.
     */
    @Nullable
    public GroupKey resolveGroup(@Nullable GroupKey current,
                                 @Nonnull Ref<EntityStore> self,
                                 @Nonnull Ref<EntityStore> leader,
                                 @Nonnull String mode,
                                 @Nonnull String configuration,
                                 double spacing,
                                 @Nonnull ComponentAccessor<EntityStore> accessor) {
        ComponentType<EntityStore, FlockMembership> membershipType = FlockMembership.getComponentType();
        FlockMembership membership = membershipType == null ? null : accessor.getComponent(self, membershipType);
        UUID flockId = membership == null ? null : membership.getFlockId();
        UUIDComponent leaderIdentity = accessor.getComponent(leader, UUIDComponent.getComponentType());
        UUID leaderId = leaderIdentity == null ? null : leaderIdentity.getUuid();
        if (flockId == null || leaderId == null || !Double.isFinite(spacing) || spacing <= 0.0) {
            return null;
        }
        if (current != null && current.matches(flockId, leaderId, mode, configuration, spacing)) {
            return current;
        }
        return new GroupKey(flockId, leaderId, mode, configuration, spacing);
    }

    /** Returns the stable slot currently reserved for this active formation member. */
    public int claim(@Nonnull Ref<EntityStore> ref, @Nonnull GroupKey group, @Nonnull UUID memberId,
                     int preferredSlot, long nowMillis) {
        Pool allocation = allocation(ref, group, nowMillis);
        return allocation.assignments.claim(memberId, preferredSlot, nowMillis);
    }

    /** Reports the unsmoothed target from this callback, then performs a bounded delayed rebalance. */
    public void report(@Nonnull Ref<EntityStore> ref, @Nonnull GroupKey group, @Nonnull UUID memberId,
                       @Nonnull Vector3d position, @Nonnull Vector3d target,
                       double spacing, long nowMillis) {
        Pool allocation = allocation(ref, group, nowMillis);
        allocation.assignments.report(memberId, position, target, spacing, group.configuration(), nowMillis);
        allocation.assignments.rebalance(nowMillis);
    }

    @Nonnull
    private Pool allocation(@Nonnull Ref<EntityStore> ref, @Nonnull GroupKey key, long nowMillis) {
        State state = states.get(ref.getStore());
        prune(state, nowMillis);
        Pool pool = state.pools.get(key.pool);
        if (pool == null) {
            pool = new Pool();
            state.pools.put(key.pool, pool);
            state.order.add(key.pool);
        }
        pool.lastActiveMillis = nowMillis;
        return pool;
    }

    /** A bounded, opportunistic cleanup is enough because empty stores have no active callers. */
    private static void prune(@Nonnull State state, long nowMillis) {
        if (state.hasPruned && nowMillis >= state.lastPruneMillis
                && nowMillis - state.lastPruneMillis < GROUP_PRUNE_CADENCE_MILLIS) {
            return;
        }
        state.hasPruned = true;
        state.lastPruneMillis = nowMillis;
        int inspected = 0;
        while (!state.order.isEmpty() && inspected++ < GROUP_PRUNE_BATCH) {
            if (state.pruneCursor >= state.order.size()) {
                state.pruneCursor = 0;
            }
            PoolKey key = state.order.get(state.pruneCursor);
            Pool pool = state.pools.get(key);
            if (pool == null || (nowMillis >= pool.lastActiveMillis
                    && nowMillis - pool.lastActiveMillis > GROUP_EXPIRY_MILLIS)) {
                state.pools.remove(key);
                state.order.remove(state.pruneCursor);
                continue;
            }
            state.pruneCursor++;
        }
    }

    /**
     * Carries both the compatible-layout profile and its precomputed shared reservation-pool key.
     * Differing profiles share slots but cannot trade them through {@link FormationSlotAssignments}.
     */
    public static final class GroupKey {
        private final PoolKey pool;
        private final String configuration;
        private final double spacing;

        private GroupKey(@Nonnull UUID flockId, @Nonnull UUID leaderId, @Nonnull String mode,
                         @Nonnull String configuration, double spacing) {
            this.pool = new PoolKey(flockId, leaderId, mode);
            this.configuration = configuration;
            this.spacing = spacing;
        }

        @Nonnull
        String configuration() {
            return configuration;
        }

        boolean matches(@Nonnull UUID flockId, @Nonnull UUID leaderId, @Nonnull String mode,
                        @Nonnull String configuration, double spacing) {
            return pool.matches(flockId, leaderId, mode) && this.configuration.equals(configuration)
                    && Double.doubleToLongBits(this.spacing) == Double.doubleToLongBits(spacing);
        }
    }

    /** Stable identity for a shared numeric slot pool; profile and spacing stay outside this key. */
    private record PoolKey(@Nonnull UUID flockId, @Nonnull UUID leaderId, @Nonnull String mode) {
        boolean matches(@Nonnull UUID nextFlockId, @Nonnull UUID nextLeaderId, @Nonnull String nextMode) {
            return flockId.equals(nextFlockId) && leaderId.equals(nextLeaderId) && mode.equals(nextMode);
        }
    }

    private static final class State {
        final HashMap<PoolKey, Pool> pools = new HashMap<>();
        final ArrayList<PoolKey> order = new ArrayList<>();
        long lastPruneMillis;
        int pruneCursor;
        boolean hasPruned;
    }

    private static final class Pool {
        final FormationSlotAssignments assignments = new FormationSlotAssignments();
        long lastActiveMillis;
    }
}
