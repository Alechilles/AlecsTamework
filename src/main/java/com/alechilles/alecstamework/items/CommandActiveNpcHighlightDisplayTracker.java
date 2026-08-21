package com.alechilles.alecstamework.items;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tracks one helper proxy and one particle delivery for each active highlight target. */
final class CommandActiveNpcHighlightDisplayTracker<T> {
    private final Map<Object, Map<UUID, PlayerState<T>>> statesByStore =
            new IdentityHashMap<>();

    /** Reconciles the desired roster and returns helper proxies that are no longer needed. */
    @Nonnull
    synchronized List<UUID> reconcile(@Nonnull Object storeIdentity,
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
            return List.of();
        }
        PlayerState<T> next = new PlayerState<>(toolId, List.copyOf(targets));
        java.util.ArrayList<UUID> staleProxies = new java.util.ArrayList<>();
        if (current != null) {
            for (Map.Entry<T, ProxyDisplay> entry : current.displays.entrySet()) {
                if (toolId.equals(current.toolId) && next.targets.contains(entry.getKey())) {
                    next.displays.put(entry.getKey(), entry.getValue());
                } else {
                    staleProxies.add(entry.getValue().proxyUuid);
                }
            }
        }
        states.put(playerUuid, next);
        return List.copyOf(staleProxies);
    }

    synchronized boolean beginProxyCreation(@Nonnull Object storeIdentity,
                                            @Nonnull UUID playerUuid,
                                            @Nonnull T target,
                                            @Nonnull UUID parentNpcUuid) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state == null || !state.targets.contains(target) || state.displays.containsKey(target)) {
            return false;
        }
        UUID pendingParent = state.pendingParents.get(target);
        if (parentNpcUuid.equals(pendingParent)) {
            return false;
        }
        state.pendingParents.put(target, parentNpcUuid);
        return true;
    }

    synchronized boolean recordProxy(@Nonnull Object storeIdentity,
                                     @Nonnull UUID playerUuid,
                                     @Nonnull T target,
                                     @Nonnull UUID parentNpcUuid,
                                     @Nonnull UUID proxyUuid) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state == null || !state.targets.contains(target)
                || !parentNpcUuid.equals(state.pendingParents.remove(target))) {
            return false;
        }
        state.displays.put(target, new ProxyDisplay(parentNpcUuid, proxyUuid, null));
        return true;
    }

    synchronized void cancelProxyCreation(@Nonnull Object storeIdentity,
                                          @Nonnull UUID playerUuid,
                                          @Nonnull T target,
                                          @Nonnull UUID parentNpcUuid) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state != null) {
            state.pendingParents.remove(target, parentNpcUuid);
        }
    }

    @Nullable
    synchronized UUID proxyUuid(@Nonnull Object storeIdentity,
                                @Nonnull UUID playerUuid,
                                @Nonnull T target,
                                @Nonnull UUID parentNpcUuid) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        ProxyDisplay display = state != null ? state.displays.get(target) : null;
        return display != null && parentNpcUuid.equals(display.parentNpcUuid)
                ? display.proxyUuid
                : null;
    }

    @Nonnull
    synchronized List<UUID> forgetProxyForDifferentParent(
            @Nonnull Object storeIdentity,
            @Nonnull UUID playerUuid,
            @Nonnull T target,
            @Nonnull UUID parentNpcUuid) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        ProxyDisplay display = state != null ? state.displays.get(target) : null;
        if (display == null || parentNpcUuid.equals(display.parentNpcUuid)) {
            return List.of();
        }
        state.displays.remove(target);
        return List.of(display.proxyUuid);
    }

    synchronized boolean needsEmission(@Nonnull Object storeIdentity,
                                       @Nonnull UUID playerUuid,
                                       @Nonnull T target,
                                       int networkId) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state == null || !state.targets.contains(target)) {
            return false;
        }
        ProxyDisplay display = state.displays.get(target);
        return display != null && !Objects.equals(display.emittedNetworkId, networkId);
    }

    synchronized void recordEmission(@Nonnull Object storeIdentity,
                                     @Nonnull UUID playerUuid,
                                     @Nonnull T target,
                                     int networkId) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state != null && state.targets.contains(target)) {
            ProxyDisplay display = state.displays.get(target);
            if (display != null) {
                state.displays.put(target, new ProxyDisplay(
                        display.parentNpcUuid,
                        display.proxyUuid,
                        networkId
                ));
            }
        }
    }

    @Nonnull
    synchronized List<UUID> forgetTarget(@Nonnull Object storeIdentity,
                                         @Nonnull UUID playerUuid,
                                         @Nonnull T target) {
        PlayerState<T> state = state(storeIdentity, playerUuid);
        if (state == null) {
            return List.of();
        }
        state.pendingParents.remove(target);
        ProxyDisplay removed = state.displays.remove(target);
        return removed == null ? List.of() : List.of(removed.proxyUuid);
    }

    @Nonnull
    synchronized List<UUID> remove(@Nonnull Object storeIdentity, @Nonnull UUID playerUuid) {
        Map<UUID, PlayerState<T>> states = statesByStore.get(storeIdentity);
        if (states == null) {
            return List.of();
        }
        PlayerState<T> removed = states.remove(playerUuid);
        if (states.isEmpty()) {
            statesByStore.remove(storeIdentity);
        }
        return removed == null ? List.of() : removed.proxyUuids();
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
        private final Map<T, ProxyDisplay> displays = new HashMap<>();
        private final Map<T, UUID> pendingParents = new HashMap<>();

        private PlayerState(@Nonnull String toolId, @Nonnull List<T> targets) {
            this.toolId = toolId;
            this.targets = targets;
        }

        @Nonnull
        private List<UUID> proxyUuids() {
            return displays.values().stream().map(display -> display.proxyUuid).toList();
        }
    }

    private record ProxyDisplay(
            @Nonnull UUID parentNpcUuid,
            @Nonnull UUID proxyUuid,
            @Nullable Integer emittedNetworkId
    ) {
    }
}
