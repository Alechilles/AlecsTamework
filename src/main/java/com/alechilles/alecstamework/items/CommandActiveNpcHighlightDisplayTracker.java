package com.alechilles.alecstamework.items;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Tracks the model particles that each command user has already received. */
final class CommandActiveNpcHighlightDisplayTracker<T> {
    private final Map<Object, Map<UUID, PlayerState<T>>> statesByStore =
            new IdentityHashMap<>();

    /**
     * Replaces the desired roster when it changes.
     *
     * @return true when previously emitted particles must be cancelled
     */
    synchronized boolean reconcile(@Nonnull Object storeIdentity,
                                   @Nonnull UUID playerUuid,
                                   @Nonnull String toolId,
                                   @Nonnull List<T> targets) {
        Objects.requireNonNull(storeIdentity, "storeIdentity");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(toolId, "toolId");
        Objects.requireNonNull(targets, "targets");
        Map<UUID, PlayerState<T>> states = statesByStore.computeIfAbsent(
                storeIdentity, ignored -> new HashMap<>()
        );
        PlayerState<T> current = states.get(playerUuid);
        if (current != null
                && toolId.equals(current.toolId)
                && targets.equals(current.targets)) {
            return false;
        }
        boolean needsCancellation = current != null && !current.renderedNetworkIds.isEmpty();
        states.put(playerUuid, new PlayerState<>(toolId, List.copyOf(targets)));
        return needsCancellation;
    }

    synchronized boolean needsEmission(@Nonnull Object storeIdentity,
                                       @Nonnull UUID playerUuid,
                                       @Nonnull T target,
                                       int networkId) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state == null || !state.targets.contains(target)) {
            return false;
        }
        return !Integer.valueOf(networkId).equals(state.renderedNetworkIds.get(target));
    }

    synchronized void recordEmission(@Nonnull Object storeIdentity,
                                     @Nonnull UUID playerUuid,
                                     @Nonnull T target,
                                     int networkId) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state != null && state.targets.contains(target)) {
            state.renderedNetworkIds.put(target, networkId);
        }
    }

    synchronized void forgetTarget(@Nonnull Object storeIdentity,
                                   @Nonnull UUID playerUuid,
                                   @Nonnull T target) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state != null) {
            state.renderedNetworkIds.remove(target);
        }
    }

    synchronized boolean remove(@Nonnull Object storeIdentity, @Nonnull UUID playerUuid) {
        Map<UUID, PlayerState<T>> states = statesByStore.get(storeIdentity);
        if (states == null) {
            return false;
        }
        PlayerState<T> removed = states.remove(playerUuid);
        if (states.isEmpty()) {
            statesByStore.remove(storeIdentity);
        }
        return removed != null && !removed.renderedNetworkIds.isEmpty();
    }

    synchronized void clear(@Nonnull Object storeIdentity) {
        statesByStore.remove(storeIdentity);
    }

    private PlayerState<T> state(@Nonnull Object storeIdentity, @Nonnull UUID playerUuid) {
        Map<UUID, PlayerState<T>> states = statesByStore.get(storeIdentity);
        return states != null ? states.get(playerUuid) : null;
    }

    private static final class PlayerState<T> {
        private final String toolId;
        private final List<T> targets;
        private final Map<T, Integer> renderedNetworkIds = new HashMap<>();

        private PlayerState(@Nonnull String toolId, @Nonnull List<T> targets) {
            this.toolId = toolId;
            this.targets = targets;
        }
    }
}
