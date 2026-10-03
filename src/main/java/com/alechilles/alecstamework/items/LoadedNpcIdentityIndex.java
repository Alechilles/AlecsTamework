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
 * <p>The index deliberately retains no live ECS objects. A UUID with no evidence is
 * {@link ProbeStatus#UNKNOWN}, never "absent": the index only hears events, so a miss does not
 * prove the NPC is not loaded.
 */
public final class LoadedNpcIdentityIndex {
    private static final Comparator<Location> LOCATION_ORDER = Comparator
            .comparing(Location::worldName)
            .thenComparing(Location::storeIdentity);
    private final Object lock = new Object();
    // Retained for callers that only know one UUID and a location.
    private final Map<UUID, Set<Location>> locationsByNpc = new HashMap<>();
    private final Map<Location, Set<LoadedNpcObservation>> observationsByLocation = new HashMap<>();
    private final Map<UUID, Set<LoadedNpcObservation>> observationsByNpc = new HashMap<>();
    private final Map<ObservationIdentity, LoadedNpcObservation> observationByIdentity = new HashMap<>();

    /** Records an NPC at one exact world/store location. Duplicate add replay is harmless. */
    public void recordAdded(@Nullable UUID npcUuid, @Nullable Location location) {
        if (npcUuid == null || location == null) {
            return;
        }
        synchronized (lock) {
            locationsByNpc.computeIfAbsent(npcUuid, ignored -> new HashSet<>()).add(location);
        }
    }
    /**
     * Records one loaded entity. Re-observing the same stable UUID at the same location replaces
     * the older observation; duplicate add replay is harmless.
     */
    public void recordAdded(@Nullable LoadedNpcObservation observation) {
        if (observation == null) {
            return;
        }
        synchronized (lock) {
            indexObservationLocked(observation);
        }
    }
    /** Removes only the matching world/store evidence. Duplicate or stale remove replay is harmless. */
    public void recordRemoved(@Nullable UUID npcUuid, @Nullable Location location) {
        if (npcUuid == null || location == null) {
            return;
        }
        synchronized (lock) {
            Set<Location> locations = locationsByNpc.get(npcUuid);
            if (locations != null) {
                locations.remove(location);
            }
            if (locations != null && locations.isEmpty()) {
                locationsByNpc.remove(npcUuid);
            }
            removeObservationsLocked(
                    location,
                    observation -> npcUuid.equals(observation.componentUuid())
                            || npcUuid.equals(observation.legacyNpcUuid())
            );
        }
    }
    /** Removes the matching entity observation without depending on its marker still being present. */
    public void recordRemoved(@Nullable LoadedNpcObservation observation) {
        if (observation == null) {
            return;
        }
        synchronized (lock) {
            removeExactObservationLocked(observation);
            removeLegacyLocationLocked(observation.componentUuid(), observation.location());
            removeLegacyLocationLocked(observation.legacyNpcUuid(), observation.location());
        }
    }
    /**
     * Clears all evidence for an explicitly retired store location.
     *
     * <p>Callers must only use this after authoritative store retirement, or from an uncancelled
     * world-removal listener registered at the terminal short priority. Earlier cancellable
     * world-removal notifications alone are not sufficient evidence.
     */
    public void clearLocation(@Nullable Location location) {
        if (location == null) {
            return;
        }
        synchronized (lock) {
            clearLocationLocked(location);
        }
    }
    /** Atomically reconciles one store location to exactly the supplied entity observations. */
    public void replaceLocationObservations(
            @Nonnull Location location,
            @Nonnull Collection<LoadedNpcObservation> observations) {
        Set<LoadedNpcObservation> replacement = validatedObservations(location, observations);
        synchronized (lock) {
            clearLocationLocked(location);
            for (LoadedNpcObservation observation : replacement) {
                indexObservationLocked(observation);
            }
        }
    }
    @Nonnull
    private static Set<LoadedNpcObservation> validatedObservations(@Nonnull Location location,
            @Nonnull Collection<LoadedNpcObservation> observations) {
        Objects.requireNonNull(location, "location");
        Set<LoadedNpcObservation> replacement = new HashSet<>();
        for (LoadedNpcObservation observation : Objects.requireNonNull(observations, "observations")) {
            LoadedNpcObservation required = Objects.requireNonNull(observation, "observation");
            if (!location.equals(required.location())) {
                throw new IllegalArgumentException("Observation location must match the replaced location.");
            }
            replacement.add(required);
        }
        return replacement;
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
        Set<LoadedNpcObservation> removed = observationsByLocation.remove(location);
        if (removed != null) {
            for (LoadedNpcObservation observation : removed) {
                observationByIdentity.remove(ObservationIdentity.of(observation), observation);
                deindexObservationByNpcLocked(observation);
            }
        }
    }
    /** Returns a deterministic immutable view of the current evidence for one UUID. */
    @Nonnull
    public Probe probe(@Nullable UUID npcUuid) {
        synchronized (lock) {
            Set<Location> locations = new HashSet<>();
            Set<Location> legacyLocations = npcUuid != null ? locationsByNpc.get(npcUuid) : null;
            if (legacyLocations != null) {
                locations.addAll(legacyLocations);
            }
            Set<LoadedNpcObservation> observations = npcUuid != null
                    ? observationsByNpc.get(npcUuid) : null;
            if (observations != null) {
                for (LoadedNpcObservation observation : observations) {
                    locations.add(observation.location());
                }
            }
            if (locations.isEmpty()) {
                return new Probe(npcUuid, ProbeStatus.UNKNOWN, List.of());
            }
            List<Location> ordered = new ArrayList<>(locations);
            ordered.sort(LOCATION_ORDER);
            ProbeStatus status = ordered.size() == 1
                    ? ProbeStatus.ONE_LOCATION
                    : ProbeStatus.MULTIPLE_LOCATIONS;
            return new Probe(npcUuid, status, ordered);
        }
    }
    private void indexObservationLocked(@Nonnull LoadedNpcObservation observation) {
        ObservationIdentity identity = ObservationIdentity.of(observation);
        LoadedNpcObservation prior = observationByIdentity.get(identity);
        if (observation.equals(prior)) {
            return;
        }
        if (prior != null) {
            removeObservationIndexesLocked(prior);
        }
        observationByIdentity.put(identity, observation);
        Set<LoadedNpcObservation> atLocation = observationsByLocation.computeIfAbsent(
                observation.location(),
                ignored -> new HashSet<>()
        );
        if (!atLocation.add(observation)) {
            return;
        }
        for (UUID npcUuid : observation.identityUuids()) {
            observationsByNpc.computeIfAbsent(npcUuid, ignored -> new HashSet<>()).add(observation);
        }
    }
    private void deindexObservationByNpcLocked(@Nonnull LoadedNpcObservation observation) {
        for (UUID npcUuid : observation.identityUuids()) {
            Set<LoadedNpcObservation> observations = observationsByNpc.get(npcUuid);
            if (observations == null) {
                continue;
            }
            observations.remove(observation);
            if (observations.isEmpty()) {
                observationsByNpc.remove(npcUuid);
            }
        }
    }
    private void removeExactObservationLocked(@Nonnull LoadedNpcObservation expected) {
        ObservationIdentity identity = ObservationIdentity.of(expected);
        LoadedNpcObservation removed = observationByIdentity.remove(identity);
        if (removed != null) {
            removeObservationIndexesLocked(removed);
        }
    }
    private void removeObservationIndexesLocked(@Nonnull LoadedNpcObservation observation) {
        observationByIdentity.remove(ObservationIdentity.of(observation), observation);
        Set<LoadedNpcObservation> atLocation = observationsByLocation.get(observation.location());
        if (atLocation != null) {
            atLocation.remove(observation);
            if (atLocation.isEmpty()) {
                observationsByLocation.remove(observation.location());
            }
        }
        deindexObservationByNpcLocked(observation);
    }

    private void removeObservationsLocked(
            @Nonnull Location location,
            @Nonnull java.util.function.Predicate<LoadedNpcObservation> predicate) {
        Set<LoadedNpcObservation> observations = observationsByLocation.get(location);
        if (observations == null) {
            return;
        }
        Iterator<LoadedNpcObservation> iterator = observations.iterator();
        while (iterator.hasNext()) {
            LoadedNpcObservation observation = iterator.next();
            if (predicate.test(observation)) {
                iterator.remove();
                observationByIdentity.remove(ObservationIdentity.of(observation), observation);
                deindexObservationByNpcLocked(observation);
            }
        }
        if (observations.isEmpty()) {
            observationsByLocation.remove(location);
        }
    }
    private void removeLegacyLocationLocked(@Nullable UUID npcUuid, @Nonnull Location location) {
        if (npcUuid == null) {
            return;
        }
        Set<Location> locations = locationsByNpc.get(npcUuid);
        if (locations == null) {
            return;
        }
        locations.remove(location);
        if (locations.isEmpty()) {
            locationsByNpc.remove(npcUuid);
        }
    }

    /** Evidence state for one UUID probe. */
    public enum ProbeStatus { UNKNOWN, ONE_LOCATION, MULTIPLE_LOCATIONS }

    private record ObservationIdentity(@Nonnull Location location, @Nonnull UUID stableIdentity) {
        private static ObservationIdentity of(@Nonnull LoadedNpcObservation observation) {
            return new ObservationIdentity(observation.location(), observation.stableIdentity());
        }
    }

    /** Stable metadata identifying one loaded entity store without retaining that store. */
    public record Location(@Nonnull String worldName, @Nonnull String storeIdentity) {
        public Location {
            worldName = normalize(worldName, "unknown");
            storeIdentity = normalize(storeIdentity, "unknown-store");
        }

        @Nonnull
        public String displayName() { return worldName + " [" + storeIdentity + "]"; }
        @Nonnull
        private static String normalize(@Nullable String value, @Nonnull String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value.trim();
        }
    }
    /** Immutable observation of one loaded NPC at one location. */
    public record LoadedNpcObservation(@Nullable UUID componentUuid,
                                       @Nullable UUID legacyNpcUuid,
                                       @Nonnull Location location) {
        public LoadedNpcObservation {
            if (componentUuid == null && legacyNpcUuid == null) {
                throw new IllegalArgumentException("At least one NPC UUID must be present.");
            }
            location = Objects.requireNonNull(location, "location");
        }
        @Nonnull
        private UUID stableIdentity() {
            return componentUuid != null ? componentUuid : Objects.requireNonNull(legacyNpcUuid);
        }
        @Nonnull
        private Set<UUID> identityUuids() {
            if (componentUuid == null) {
                return Set.of(Objects.requireNonNull(legacyNpcUuid));
            }
            if (legacyNpcUuid == null || componentUuid.equals(legacyNpcUuid)) {
                return Set.of(componentUuid);
            }
            return Set.of(componentUuid, legacyNpcUuid);
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
        public int locationCount() { return locations.size(); }
        @Nonnull
        public List<String> locationNames() {
            return locations.stream().map(Location::displayName).toList(); }
        @Nonnull
        public List<String> worldNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (Location location : locations) {
                names.add(location.worldName());
            }
            return List.copyOf(names);
        }
        public int worldCount() { return worldNames().size(); }
        public boolean isKnownLive() {
            return status == ProbeStatus.ONE_LOCATION || status == ProbeStatus.MULTIPLE_LOCATIONS; }

        public boolean hasLocationConflict() { return status == ProbeStatus.MULTIPLE_LOCATIONS; }
    }
}
