package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event-maintained index of loaded NPC UUIDs and their immutable world/store locations.
 *
 * <p>The index deliberately retains no live ECS objects. Probes remain {@link ProbeStatus#UNKNOWN}
 * until an external store bootstrap marks initialization complete, so partial event observation cannot
 * misreport a UUID as absent.
 */
public final class LoadedNpcIdentityIndex {
    private static final Comparator<Location> LOCATION_ORDER = Comparator
            .comparing(Location::worldName)
            .thenComparing(Location::storeIdentity);

    private final Object lock = new Object();
    private final Map<UUID, Set<Location>> locationsByNpc = new HashMap<>();
    private boolean initializationComplete;

    /** Marks a separately performed store bootstrap complete, making future misses authoritative. */
    public void markInitializationComplete() {
        synchronized (lock) {
            initializationComplete = true;
        }
    }

    /** Revokes authoritative absence while one or more entity stores are being enumerated. */
    public void markInitializationIncomplete() {
        synchronized (lock) {
            initializationComplete = false;
        }
    }

    /** Records an NPC at one exact world/store location. Duplicate add replay is harmless. */
    public void recordAdded(@Nullable UUID npcUuid, @Nullable Location location) {
        if (npcUuid == null || location == null) {
            return;
        }
        synchronized (lock) {
            locationsByNpc.computeIfAbsent(npcUuid, ignored -> new HashSet<>()).add(location);
        }
    }

    /** Removes only the matching world/store evidence. Duplicate or stale remove replay is harmless. */
    public void recordRemoved(@Nullable UUID npcUuid, @Nullable Location location) {
        if (npcUuid == null || location == null) {
            return;
        }
        synchronized (lock) {
            Set<Location> locations = locationsByNpc.get(npcUuid);
            if (locations == null || !locations.remove(location)) {
                return;
            }
            if (locations.isEmpty()) {
                locationsByNpc.remove(npcUuid);
            }
        }
    }

    /**
     * Clears all evidence for an explicitly retired store location.
     *
     * <p>Callers must only use this after authoritative store retirement; cancellable world-removal
     * notifications alone are not sufficient evidence.
     */
    public void clearLocation(@Nullable Location location) {
        if (location == null) {
            return;
        }
        synchronized (lock) {
            clearLocationLocked(location);
        }
    }

    /** Atomically reconciles one store location to exactly the supplied UUID evidence. */
    public void replaceLocation(@Nonnull Location location, @Nonnull Collection<UUID> npcUuids) {
        Objects.requireNonNull(location, "location");
        Set<UUID> replacement = new HashSet<>();
        for (UUID npcUuid : Objects.requireNonNull(npcUuids, "npcUuids")) {
            if (npcUuid != null) {
                replacement.add(npcUuid);
            }
        }
        synchronized (lock) {
            clearLocationLocked(location);
            for (UUID npcUuid : replacement) {
                locationsByNpc.computeIfAbsent(npcUuid, ignored -> new HashSet<>()).add(location);
            }
        }
    }

    private void clearLocationLocked(@Nonnull Location location) {
        Iterator<Map.Entry<UUID, Set<Location>>> entries = locationsByNpc.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Set<Location>> entry = entries.next();
            entry.getValue().remove(location);
            if (entry.getValue().isEmpty()) {
                entries.remove();
            }
        }
    }

    /** Returns a deterministic immutable view of the current evidence for one UUID. */
    @Nonnull
    public Probe probe(@Nullable UUID npcUuid) {
        synchronized (lock) {
            Set<Location> locations = npcUuid != null ? locationsByNpc.get(npcUuid) : null;
            if (locations == null || locations.isEmpty()) {
                ProbeStatus missingStatus = initializationComplete
                        ? ProbeStatus.ABSENT
                        : ProbeStatus.UNKNOWN;
                return new Probe(npcUuid, missingStatus, List.of());
            }
            List<Location> ordered = new ArrayList<>(locations);
            ordered.sort(LOCATION_ORDER);
            ProbeStatus status = ordered.size() == 1
                    ? ProbeStatus.ONE_LOCATION
                    : ProbeStatus.MULTIPLE_LOCATIONS;
            return new Probe(npcUuid, status, ordered);
        }
    }

    public boolean isInitializationComplete() {
        synchronized (lock) {
            return initializationComplete;
        }
    }

    /** Completeness/conflict state for one UUID probe. */
    public enum ProbeStatus {
        UNKNOWN,
        ABSENT,
        ONE_LOCATION,
        MULTIPLE_LOCATIONS
    }

    /** Stable metadata identifying one loaded entity store without retaining that store. */
    public record Location(@Nonnull String worldName, @Nonnull String storeIdentity) {
        public Location {
            worldName = normalize(worldName, "unknown");
            storeIdentity = normalize(storeIdentity, "unknown-store");
        }

        @Nonnull
        public String displayName() {
            return worldName + " [" + storeIdentity + "]";
        }

        @Nonnull
        private static String normalize(@Nullable String value, @Nonnull String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value.trim();
        }
    }

    /** Immutable probe result with deterministic location ordering and presentation metadata. */
    public record Probe(@Nullable UUID npcUuid,
                        @Nonnull ProbeStatus status,
                        @Nonnull List<Location> locations) {
        public Probe {
            status = Objects.requireNonNull(status, "status");
            locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
        }

        public int locationCount() {
            return locations.size();
        }

        @Nonnull
        public List<String> locationNames() {
            return locations.stream().map(Location::displayName).toList();
        }

        @Nonnull
        public List<String> worldNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (Location location : locations) {
                names.add(location.worldName());
            }
            return List.copyOf(names);
        }

        public int worldCount() {
            return worldNames().size();
        }

        public boolean isKnownLive() {
            return status == ProbeStatus.ONE_LOCATION || status == ProbeStatus.MULTIPLE_LOCATIONS;
        }

        public boolean hasLocationConflict() {
            return status == ProbeStatus.MULTIPLE_LOCATIONS;
        }
    }
}
