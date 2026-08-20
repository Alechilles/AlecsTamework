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
    // Retained for callers that only know one UUID and a location.
    private final Map<UUID, Set<Location>> locationsByNpc = new HashMap<>();
    private final Map<Location, Set<LoadedNpcObservation>> observationsByLocation = new HashMap<>();
    private final Map<UUID, Set<LoadedNpcObservation>> observationsByNpc = new HashMap<>();
    private final Map<String, Set<LoadedNpcObservation>> observationsByProfile = new HashMap<>();
    private final Map<UUID, Set<LoadedNpcObservation>> observationsBySourceNpc = new HashMap<>();
    private final Map<ObservationIdentity, LoadedNpcObservation> observationByIdentity = new HashMap<>();
    private final Map<Location, Long> mutationRevisionByLocation = new HashMap<>();
    private long mutationRevision;
    private boolean initializationComplete;

    /** Marks a separately performed store bootstrap complete, making future misses authoritative. */
    public void markInitializationComplete() {
        synchronized (lock) {
            if (!initializationComplete) {
                initializationComplete = true;
                mutationRevision++;
            }
        }
    }
    /** Revokes authoritative absence while one or more entity stores are being enumerated. */
    public void markInitializationIncomplete() {
        synchronized (lock) {
            if (initializationComplete) {
                initializationComplete = false;
                mutationRevision++;
            }
        }
    }
    /** Records an NPC at one exact world/store location. Duplicate add replay is harmless. */
    public void recordAdded(@Nullable UUID npcUuid, @Nullable Location location) {
        if (npcUuid == null || location == null) {
            return;
        }
        synchronized (lock) {
            advanceMutationRevisionLocked(location);
            locationsByNpc.computeIfAbsent(npcUuid, ignored -> new HashSet<>()).add(location);
        }
    }
    /**
     * Records one loaded entity, including its optional durable projection identity.
     *
     * <p>Re-observing the same component UUID at the same location replaces its older marker
     * snapshot. Distinct component UUIDs remain distinct even when they carry the same marker,
     * allowing projection probes to report duplicate live entities.</p>
     */
    public void recordAdded(@Nullable LoadedNpcObservation observation) {
        if (observation == null) {
            return;
        }
        synchronized (lock) {
            advanceMutationRevisionLocked(observation.location());
            indexObservationLocked(observation);
        }
    }
    /** Removes only the matching world/store evidence. Duplicate or stale remove replay is harmless. */
    public void recordRemoved(@Nullable UUID npcUuid, @Nullable Location location) {
        if (npcUuid == null || location == null) {
            return;
        }
        synchronized (lock) {
            advanceMutationRevisionLocked(location);
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
            advanceMutationRevisionLocked(observation.location());
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
            advanceMutationRevisionLocked(location);
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
            advanceMutationRevisionLocked(location);
            clearLocationLocked(location);
            for (UUID npcUuid : replacement) {
                locationsByNpc.computeIfAbsent(npcUuid, ignored -> new HashSet<>()).add(location);
            }
        }
    }
    /** Atomically reconciles one store location to exactly the supplied entity observations. */
    public void replaceLocationObservations(
            @Nonnull Location location,
            @Nonnull Collection<LoadedNpcObservation> observations) {
        Set<LoadedNpcObservation> replacement = validatedObservations(location, observations);
        synchronized (lock) {
            advanceMutationRevisionLocked(location);
            clearLocationLocked(location);
            for (LoadedNpcObservation observation : replacement) {
                indexObservationLocked(observation);
            }
        }
    }
    /** Captures the lifecycle-mutation revision used to linearize one location scan. */
    public long locationMutationRevision(@Nonnull Location location) {
        synchronized (lock) {
            return mutationRevisionByLocation.getOrDefault(
                    Objects.requireNonNull(location, "location"), 0L);
        }
    }

    /** Confirms that no loaded identity or projection evidence changed since capture. */
    public boolean isMutationRevisionCurrent(long expectedRevision) {
        synchronized (lock) { return mutationRevision == expectedRevision; }
    }
    /** Atomically snapshots completeness and every detailed loaded-NPC observation. */
    @Nonnull
    public LoadedNpcIdentitySnapshot snapshot() {
        synchronized (lock) {
            List<LoadedNpcObservation> observations = observationsByLocation.values().stream()
                    .flatMap(Collection::stream).sorted(LoadedNpcObservationOrder.COMPARATOR).toList();
            return new LoadedNpcIdentitySnapshot(
                    mutationRevision, initializationComplete, observations);
        }
    }
    /** Replaces one scan snapshot only when no lifecycle callback changed that location. */
    public boolean replaceLocationObservationsIfUnchanged(@Nonnull Location location,
            @Nonnull Collection<LoadedNpcObservation> observations,
            long expectedRevision) {
        Set<LoadedNpcObservation> replacement = validatedObservations(location, observations);
        synchronized (lock) {
            if (expectedRevision != mutationRevisionByLocation.getOrDefault(location, 0L)) {
                return false;
            }
            advanceMutationRevisionLocked(location);
            clearLocationLocked(location);
            for (LoadedNpcObservation observation : replacement) {
                indexObservationLocked(observation);
            }
            return true;
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
    private void advanceMutationRevisionLocked(@Nonnull Location location) {
        mutationRevision++;
        mutationRevisionByLocation.merge(location, 1L, Long::sum);
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
                deindexObservationByProfileLocked(observation);
                deindexObservationBySourceNpcLocked(observation);
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
    /** Returns all loaded entities carrying one exact durable projection marker. */
    @Nonnull
    public ProjectionProbe probeProjection(@Nonnull ProjectionKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            List<LoadedNpcObservation> matches = new ArrayList<>();
            for (Set<LoadedNpcObservation> observations : observationsByLocation.values()) {
                for (LoadedNpcObservation observation : observations) {
                    if (key.equals(observation.projectionKey())) {
                        matches.add(observation);
                    }
                }
            }
            matches.sort(LoadedNpcObservationOrder.COMPARATOR);
            ProjectionProbeStatus status;
            if (matches.isEmpty()) {
                status = initializationComplete
                        ? ProjectionProbeStatus.ABSENT
                        : ProjectionProbeStatus.UNKNOWN;
            } else if (matches.size() == 1) {
                status = ProjectionProbeStatus.ONE_MATCH;
            } else {
                status = ProjectionProbeStatus.MULTIPLE_MATCHES;
            }
            return new ProjectionProbe(key, status, matches);
        }
    }
    /** Returns the only loaded NPC for a stable profile or historical source alias. */
    @Nullable
    UUID uniqueNpcUuidForRecord(@Nullable String profileId, @Nullable UUID recordedNpcUuid) {
        String normalized = profileId == null ? null : profileId.trim();
        synchronized (lock) {
            Set<LoadedNpcObservation> profileMatches = normalized == null || normalized.isEmpty()
                    ? null : observationsByProfile.get(normalized);
            LoadedNpcObservation match = onlyMatch(profileMatches);
            if (hasMultipleMatches(profileMatches)) {
                return null;
            }
            Set<LoadedNpcObservation> recordedAliasMatches =
                    observationsBySourceNpc.get(recordedNpcUuid);
            if (conflictsWith(match, recordedAliasMatches)) {
                return null;
            }
            if (match == null) {
                match = onlyMatch(recordedAliasMatches);
            }
            Set<LoadedNpcObservation> profileAliasMatches =
                    observationsBySourceNpc.get(parseUuid(normalized));
            if (conflictsWith(match, profileAliasMatches)) {
                return null;
            }
            if (match == null) {
                match = onlyMatch(profileAliasMatches);
            }
            if (match == null) {
                return null;
            }
            return match.componentUuid() != null
                    ? match.componentUuid()
                    : match.legacyUuid();
        }
    }

    @Nullable
    private static LoadedNpcObservation onlyMatch(
            @Nullable Set<LoadedNpcObservation> candidates) {
        return candidates != null && candidates.size() == 1
                ? candidates.iterator().next()
                : null;
    }

    private static boolean hasMultipleMatches(
            @Nullable Set<LoadedNpcObservation> candidates) {
        return candidates != null && candidates.size() > 1;
    }

    private static boolean conflictsWith(
            @Nullable LoadedNpcObservation current,
            @Nullable Set<LoadedNpcObservation> candidates) {
        if (hasMultipleMatches(candidates)) {
            return true;
        }
        LoadedNpcObservation candidate = onlyMatch(candidates);
        return current != null && candidate != null && !current.equals(candidate);
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
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
        ProjectionKey projectionKey = observation.projectionKey();
        if (projectionKey != null) {
            observationsByProfile.computeIfAbsent(
                    projectionKey.profileId(), ignored -> new HashSet<>()).add(observation);
            UUID sourceNpcUuid = projectionKey.sourceNpcUuid();
            if (sourceNpcUuid != null) {
                observationsBySourceNpc.computeIfAbsent(
                        sourceNpcUuid, ignored -> new HashSet<>()).add(observation);
            }
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
        deindexObservationByProfileLocked(observation);
        deindexObservationBySourceNpcLocked(observation);
    }

    private void deindexObservationByProfileLocked(@Nonnull LoadedNpcObservation observation) {
        ProjectionKey projectionKey = observation.projectionKey();
        if (projectionKey == null) {
            return;
        }
        Set<LoadedNpcObservation> observations = observationsByProfile.get(projectionKey.profileId());
        if (observations == null) {
            return;
        }
        observations.remove(observation);
        if (observations.isEmpty()) {
            observationsByProfile.remove(projectionKey.profileId());
        }
    }

    private void deindexObservationBySourceNpcLocked(@Nonnull LoadedNpcObservation observation) {
        ProjectionKey projectionKey = observation.projectionKey();
        UUID sourceNpcUuid = projectionKey != null ? projectionKey.sourceNpcUuid() : null;
        if (sourceNpcUuid == null) {
            return;
        }
        Set<LoadedNpcObservation> observations = observationsBySourceNpc.get(sourceNpcUuid);
        if (observations == null) {
            return;
        }
        observations.remove(observation);
        if (observations.isEmpty()) {
            observationsBySourceNpc.remove(sourceNpcUuid);
        }
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
                deindexObservationByProfileLocked(observation);
                deindexObservationBySourceNpcLocked(observation);
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
    public boolean isInitializationComplete() {
        synchronized (lock) {
            return initializationComplete;
        }
    }

    /** Completeness/conflict state for one UUID probe. */
    public enum ProbeStatus { UNKNOWN, ABSENT, ONE_LOCATION, MULTIPLE_LOCATIONS }

    /** Completeness/conflict state for one exact projection-marker probe. */
    public enum ProjectionProbeStatus { UNKNOWN, ABSENT, ONE_MATCH, MULTIPLE_MATCHES }

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
    /** Immutable durable marker identity used to correlate one planned NPC projection. */
    public record ProjectionKey(@Nonnull String profileId,
                                @Nonnull String operationId,
                                @Nonnull String projectionKind,
                                @Nullable String slotKey,
                                @Nullable UUID sourceNpcUuid,
                                long generation) {
        public ProjectionKey {
            profileId = requireText(profileId, "profileId");
            operationId = requireText(operationId, "operationId");
            projectionKind = requireText(projectionKind, "projectionKind");
            slotKey = optionalText(slotKey);
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
        }
        @Nonnull
        private static String requireText(@Nullable String value, @Nonnull String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value.trim();
        }
        @Nullable
        private static String optionalText(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.trim(); }
    }

    /** Immutable observation of one loaded NPC and its optional durable projection marker. */
    public record LoadedNpcObservation(@Nullable UUID componentUuid,
                                       @Nullable UUID legacyNpcUuid,
                                       @Nonnull Location location,
                                       @Nullable ProjectionKey projectionKey) {
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
        @Nullable
        public UUID legacyUuid() { return legacyNpcUuid; }
    }

    /** Immutable exact-marker probe result with deterministic entity ordering. */
    public record ProjectionProbe(@Nonnull ProjectionKey key,
                                  @Nonnull ProjectionProbeStatus status,
                                  @Nonnull List<LoadedNpcObservation> matches) {
        public ProjectionProbe {
            key = Objects.requireNonNull(key, "key");
            status = Objects.requireNonNull(status, "status");
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
            for (LoadedNpcObservation match : matches) {
                if (match == null || !key.equals(match.projectionKey())) {
                    throw new IllegalArgumentException(
                            "Every projection match must carry the probed key."
                    );
                }
            }
            int matchCount = matches.size();
            boolean validCount = switch (status) {
                case UNKNOWN, ABSENT -> matchCount == 0;
                case ONE_MATCH -> matchCount == 1;
                case MULTIPLE_MATCHES -> matchCount > 1;
            };
            if (!validCount) {
                throw new IllegalArgumentException(
                        "Projection probe status does not match its observation count."
                );
            }
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
