package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Owns the runtime needs schedule and active companion membership for each entity store.
 *
 * <p>The registry stores stable NPC IDs only. Live references, components, stores, and worlds
 * remain owned by the current ECS callback that needs them.</p>
 */
public final class CompanionNeedsRuntimeRegistry {
    private static final long BASE_SWEEP_INTERVAL_MS = 2_000L;

    private final StoreScopedState<WorldState> statesByStore = new StoreScopedState<>(WorldState::new);

    /** Registers or refreshes one tamed companion in its store's schedule. */
    public void register(@Nonnull Store<EntityStore> store, @Nonnull UUID npcId, long nowMs) {
        state(store).register(npcId, nowMs);
    }

    /** Removes a companion and releases the store state when it was the last member. */
    public void remove(@Nonnull Store<EntityStore> store, @Nonnull UUID npcId) {
        WorldState worldState = state(store);
        worldState.remove(npcId);
        if (worldState.membership().isEmpty()) {
            clear(store);
        }
    }

    /** Returns the mutable runtime state owned by the supplied store. */
    @Nonnull
    public WorldState state(@Nonnull Store<EntityStore> store) {
        return statesByStore.get(store);
    }

    /** Clears all runtime state for a store, including queued and suppression work. */
    public void clear(@Nonnull Store<EntityStore> store) {
        WorldState worldState = statesByStore.get(store);
        worldState.clear();
        statesByStore.remove(store);
    }

    /** Creates state without a Hytale store for package-level behavior tests. */
    static WorldState newStateForTests() {
        return new WorldState();
    }

    /** Runtime state for one entity store. */
    public static final class WorldState {
        private final Set<UUID> membership = new HashSet<>();
        private final CompanionNeedsSchedule schedule = new CompanionNeedsSchedule();
        private final LinkedHashSet<UUID> suppressionIds = new LinkedHashSet<>();
        private boolean dispatchPending;

        /** Registers or refreshes an NPC using deterministic initial jitter. */
        public void register(@Nonnull UUID npcId, long nowMs) {
            Objects.requireNonNull(npcId, "npcId");
            membership.add(npcId);
            long initialDelayMs = NeedsSweepScheduler.stableOffsetMs(npcId, BASE_SWEEP_INTERVAL_MS);
            schedule.register(npcId, nowMs, initialDelayMs);
        }

        /** Removes an NPC from membership, due work, and suppression work. */
        public void remove(@Nonnull UUID npcId) {
            Objects.requireNonNull(npcId, "npcId");
            membership.remove(npcId);
            schedule.remove(npcId);
            suppressionIds.remove(npcId);
            if (membership.isEmpty()) {
                clear();
            }
        }

        /** Sets whether an NPC needs the high-frequency regeneration suppression pass. */
        public void setSuppressionActive(@Nonnull UUID npcId, boolean active) {
            Objects.requireNonNull(npcId, "npcId");
            if (active) {
                suppressionIds.add(npcId);
            } else {
                suppressionIds.remove(npcId);
            }
        }

        /** Returns the UUIDs currently requiring regeneration suppression. */
        @Nonnull
        public Set<UUID> suppressionIds() {
            return suppressionIds;
        }

        /** Returns the registered UUIDs for this store. */
        @Nonnull
        public Set<UUID> membership() {
            return membership;
        }

        /** Returns whether an NPC is currently registered in this store. */
        public boolean hasMember(@Nonnull UUID npcId) {
            return membership.contains(npcId);
        }

        /** Returns whether at least one UUID currently requires suppression. */
        public boolean hasSuppressionActive() {
            return !suppressionIds.isEmpty();
        }

        /** Returns whether a scheduled UUID is due at the supplied current time. */
        public boolean hasDue(long nowMs) {
            if (schedule.size() == 0) {
                return false;
            }
            return schedule.nextDueAtMs() <= nowMs;
        }

        /** Returns whether a world-thread batch is already queued. */
        public boolean isDispatchPending() {
            return dispatchPending;
        }

        /** Marks whether a world-thread batch is already queued. */
        public void setDispatchPending(boolean dispatchPending) {
            this.dispatchPending = dispatchPending;
        }

        /** Provides the package-private due queue to progression services. */
        CompanionNeedsSchedule schedule() {
            return schedule;
        }

        private void clear() {
            for (UUID npcId : membership) {
                schedule.remove(npcId);
            }
            membership.clear();
            suppressionIds.clear();
            dispatchPending = false;
        }
    }
}
