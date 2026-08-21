package com.alechilles.alecstamework.items;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Tracks delivery and renewal for each command user's private model particles. */
final class CommandActiveNpcHighlightDisplayTracker<T> {
    private final long renewalIntervalMs;
    private final Map<Object, Map<UUID, PlayerState<T>>> statesByStore =
            new IdentityHashMap<>();

    CommandActiveNpcHighlightDisplayTracker(long renewalIntervalMs) {
        if (renewalIntervalMs <= 0L) {
            throw new IllegalArgumentException("renewalIntervalMs must be positive");
        }
        this.renewalIntervalMs = renewalIntervalMs;
    }

    /** Replaces the desired roster and its renewal state when either changes. */
    synchronized void reconcile(@Nonnull Object storeIdentity,
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
            return;
        }
        states.put(playerUuid, new PlayerState<>(toolId, List.copyOf(targets)));
    }

    synchronized boolean needsEmission(@Nonnull Object storeIdentity,
                                       @Nonnull UUID playerUuid,
                                       @Nonnull T target,
                                       int networkId,
                                       long nowMs) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state == null || !state.targets.contains(target)) {
            return false;
        }
        Emission emission = state.emissions.get(target);
        return emission == null
                || emission.networkId != networkId
                || nowMs >= emission.nextRenewalMs;
    }

    synchronized void recordEmission(@Nonnull Object storeIdentity,
                                     @Nonnull UUID playerUuid,
                                     @Nonnull T target,
                                     int networkId,
                                     long nowMs) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state != null && state.targets.contains(target)) {
            state.emissions.put(target, new Emission(networkId, nowMs + renewalIntervalMs));
        }
    }

    synchronized void forgetTarget(@Nonnull Object storeIdentity,
                                   @Nonnull UUID playerUuid,
                                   @Nonnull T target) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state != null) {
            state.emissions.remove(target);
        }
    }

    synchronized void remove(@Nonnull Object storeIdentity, @Nonnull UUID playerUuid) {
        Map<UUID, PlayerState<T>> states = statesByStore.get(storeIdentity);
        if (states == null) {
            return;
        }
        states.remove(playerUuid);
        if (states.isEmpty()) {
            statesByStore.remove(storeIdentity);
        }
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
        private final Map<T, Emission> emissions = new HashMap<>();

        private PlayerState(@Nonnull String toolId, @Nonnull List<T> targets) {
            this.toolId = toolId;
            this.targets = targets;
        }
    }

    private record Emission(int networkId, long nextRenewalMs) {
    }
}
