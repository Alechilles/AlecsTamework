package com.alechilles.alecstamework.items;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Splits each player's active highlight roster into bounded, consecutive tick batches. */
final class CommandActiveNpcHighlightBatchService<T> {
    private final int maxBatchSize;
    private final Map<Object, Map<UUID, PlayerState<T>>> statesByStore =
            new IdentityHashMap<>();

    CommandActiveNpcHighlightBatchService(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        this.maxBatchSize = maxBatchSize;
    }

    /** Returns the next bounded slice, or an empty list until the next refresh cycle is due. */
    @Nonnull
    synchronized List<T> select(@Nonnull Object storeIdentity,
                                @Nonnull UUID playerUuid,
                                @Nonnull String toolId,
                                long nowMs,
                                long cycleIntervalMs,
                                @Nonnull Supplier<List<T>> targetLoader) {
        Objects.requireNonNull(storeIdentity, "storeIdentity");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(toolId, "toolId");
        Objects.requireNonNull(targetLoader, "targetLoader");
        Map<UUID, PlayerState<T>> states = statesByStore.computeIfAbsent(
                storeIdentity, ignored -> new HashMap<>()
        );
        PlayerState<T> state = states.get(playerUuid);
        if (state == null || !toolId.equals(state.toolId)) {
            state = new PlayerState<>(toolId, List.of(), 0, nowMs);
        }
        if (state.nextIndex == 0) {
            if (nowMs < state.nextCycleMs) {
                states.put(playerUuid, state);
                return List.of();
            }
            List<T> targets = List.copyOf(targetLoader.get());
            state = new PlayerState<>(
                    toolId,
                    targets,
                    0,
                    nowMs + Math.max(0L, cycleIntervalMs)
            );
        }
        if (state.targets.isEmpty()) {
            states.put(playerUuid, state);
            return List.of();
        }
        int count = Math.min(maxBatchSize, state.targets.size() - state.nextIndex);
        java.util.ArrayList<T> selected = new java.util.ArrayList<>(count);
        int nextIndex = state.nextIndex;
        for (int index = 0; index < count; index++) {
            selected.add(state.targets.get(nextIndex));
            nextIndex++;
        }
        if (nextIndex >= state.targets.size()) {
            nextIndex = 0;
        }
        states.put(playerUuid, new PlayerState<>(
                state.toolId, state.targets, nextIndex, state.nextCycleMs
        ));
        return List.copyOf(selected);
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

    private static final class PlayerState<T> {
        private final String toolId;
        private final List<T> targets;
        private final int nextIndex;
        private final long nextCycleMs;

        private PlayerState(String toolId, List<T> targets, int nextIndex, long nextCycleMs) {
            this.toolId = toolId;
            this.targets = targets;
            this.nextIndex = nextIndex;
            this.nextCycleMs = nextCycleMs;
        }
    }
}
