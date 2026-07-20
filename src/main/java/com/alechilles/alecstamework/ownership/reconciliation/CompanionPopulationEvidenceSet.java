package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Deduplicates copied identities and rejects conflicting owner or physical observations. */
public final class CompanionPopulationEvidenceSet {
    private final List<CompanionPopulationEvidence> allEvidence;
    private final Map<UUID, ResolvedEvidence> byNpcUuid;
    private final Map<UUID, List<CompanionPopulationEvidence>> observationsByNpcUuid;
    private final Map<String, List<ProjectionObservation>> projectionObservationsByFingerprint;
    private final List<Conflict> conflicts;

    public CompanionPopulationEvidenceSet(@Nonnull Collection<CompanionPopulationEvidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        this.allEvidence = List.copyOf(evidence);
        Map<UUID, List<CompanionPopulationEvidence>> grouped = new LinkedHashMap<>();
        Map<String, List<ProjectionObservation>> projections = new LinkedHashMap<>();
        for (CompanionPopulationEvidence observation : allEvidence) {
            Objects.requireNonNull(observation, "observation");
            CompanionProjectionEvidence.ProjectionObservation projection =
                    observation.projectionObservation();
            if (projection != null) {
                ProjectionObservation indexed = new ProjectionObservation(
                        observation,
                        projection.fingerprint(),
                        projection.componentUuid(),
                        projection.legacyNpcUuid(),
                        projection.deathObserved()
                );
                projections.computeIfAbsent(
                        projection.fingerprint(), ignored -> new ArrayList<>()
                ).add(indexed);
            }
            if (observation.kind().isProjectionMarker()) {
                continue;
            }
            grouped.computeIfAbsent(observation.npcUuid(), ignored -> new ArrayList<>()).add(observation);
        }
        Map<UUID, List<CompanionPopulationEvidence>> copied = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<CompanionPopulationEvidence>> entry : grouped.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.observationsByNpcUuid = Map.copyOf(copied);
        Map<String, List<ProjectionObservation>> copiedProjections = new LinkedHashMap<>();
        for (Map.Entry<String, List<ProjectionObservation>> entry : projections.entrySet()) {
            copiedProjections.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.projectionObservationsByFingerprint = Map.copyOf(copiedProjections);
        Map<UUID, ResolvedEvidence> resolved = new LinkedHashMap<>();
        List<Conflict> foundConflicts = new ArrayList<>();
        for (Map.Entry<UUID, List<CompanionPopulationEvidence>> entry : grouped.entrySet()) {
            Resolution resolution = resolve(entry.getKey(), entry.getValue());
            if (resolution.conflict() != null) {
                foundConflicts.add(resolution.conflict());
            } else {
                resolved.put(entry.getKey(), resolution.evidence());
            }
        }
        this.byNpcUuid = Map.copyOf(resolved);
        this.conflicts = List.copyOf(foundConflicts);
    }

    @Nonnull
    public Map<UUID, ResolvedEvidence> byNpcUuid() {
        return byNpcUuid;
    }

    @Nonnull
    public List<ResolvedEvidence> evidence() {
        return List.copyOf(byNpcUuid.values());
    }

    @Nonnull
    public List<Conflict> conflicts() {
        return conflicts;
    }

    @Nonnull
    public List<CompanionPopulationEvidence> observations(@Nonnull UUID npcUuid) {
        return observationsByNpcUuid.getOrDefault(npcUuid, List.of());
    }

    /** Returns every saved projection carrying the exact marker fingerprint. */
    @Nonnull
    public List<ProjectionObservation> projectionObservations(@Nonnull String fingerprint) {
        return projectionObservationsByFingerprint.getOrDefault(
                Objects.requireNonNull(fingerprint, "fingerprint"), List.of()
        );
    }

    public boolean isConflictFree() {
        return conflicts.isEmpty();
    }

    /** Excludes every known alias of contained conflict profiles while retaining projection markers. */
    @Nonnull
    public CompanionPopulationEvidenceSet excludingConflictUuids(
            @Nonnull Set<UUID> excludedNpcUuids
    ) {
        Objects.requireNonNull(excludedNpcUuids, "excludedNpcUuids");
        Set<UUID> conflictUuids = conflicts.stream()
                .map(Conflict::npcUuid)
                .collect(Collectors.toUnmodifiableSet());
        if (!excludedNpcUuids.containsAll(conflictUuids)) {
            throw new IllegalArgumentException("Every known conflict identity must be excluded.");
        }
        return new CompanionPopulationEvidenceSet(allEvidence.stream()
                .filter(value -> value.kind().isProjectionMarker()
                        || !excludedNpcUuids.contains(value.npcUuid()))
                .toList());
    }

    @Nonnull
    private static Resolution resolve(@Nonnull UUID npcUuid,
                                      @Nonnull List<CompanionPopulationEvidence> observations) {
        List<CompanionPopulationEvidence> physical = observations.stream()
                .filter(value -> value.kind().isPhysical())
                .toList();
        List<CompanionPopulationEvidence> authoritative = physical.isEmpty() ? observations : physical;
        if (physical.isEmpty() && conflictingDormantKinds(observations)) {
            return Resolution.conflict(npcUuid, "conflicting-dormant-lifecycle-evidence", observations);
        }
        Set<UUID> owners = new LinkedHashSet<>();
        boolean observedNullOwner = false;
        boolean ownerObserved = false;
        for (CompanionPopulationEvidence evidence : authoritative) {
            if (!evidence.ownerObserved()) {
                continue;
            }
            ownerObserved = true;
            if (evidence.ownerUuid() == null) {
                observedNullOwner = true;
            } else {
                owners.add(evidence.ownerUuid());
            }
        }
        if (owners.size() > 1 || (observedNullOwner && !owners.isEmpty())) {
            return Resolution.conflict(npcUuid, "conflicting-owner-evidence", observations);
        }

        PhysicalLocation physicalLocation = null;
        if (!physical.isEmpty()) {
            Set<PhysicalLocation> locations = new LinkedHashSet<>();
            Set<Boolean> deathStates = new LinkedHashSet<>();
            for (CompanionPopulationEvidence evidence : physical) {
                locations.add(new PhysicalLocation(
                        Objects.requireNonNull(evidence.physicalWorldName(), "physicalWorldName"),
                        Objects.requireNonNull(evidence.physicalChunkX(), "physicalChunkX").intValue(),
                        Objects.requireNonNull(evidence.physicalChunkZ(), "physicalChunkZ").intValue()
                ));
                deathStates.add(evidence.kind().isDeadPhysical());
            }
            if (locations.size() != 1) {
                return Resolution.conflict(npcUuid, "duplicate-physical-identity", observations);
            }
            if (deathStates.size() != 1) {
                return Resolution.conflict(
                        npcUuid, "conflicting-physical-death-evidence", observations
                );
            }
            physicalLocation = locations.iterator().next();
        }

        UUID ownerUuid = owners.isEmpty() ? null : owners.iterator().next();
        String ownershipWorld = physicalLocation != null ? physicalLocation.worldName() : firstOwnershipWorld(observations);
        CompanionPopulationEvidence.Kind lifecycleKind = resolveLifecycleKind(observations, !physical.isEmpty());
        Set<String> sourceKeys = new LinkedHashSet<>();
        for (CompanionPopulationEvidence observation : observations) {
            sourceKeys.add(observation.source());
        }
        return new Resolution(new ResolvedEvidence(
                npcUuid,
                ownerUuid,
                ownerObserved,
                physicalLocation != null,
                physical.stream().anyMatch(value -> value.kind().isDeadPhysical()),
                lifecycleKind,
                ownershipWorld,
                physicalLocation,
                observations.size(),
                Set.copyOf(sourceKeys)
        ), null);
    }

    private static boolean conflictingDormantKinds(
            @Nonnull List<CompanionPopulationEvidence> observations
    ) {
        Set<CompanionPopulationEvidence.Kind> activeKinds = new LinkedHashSet<>();
        for (CompanionPopulationEvidence observation : observations) {
            switch (observation.kind()) {
                case CAPTURED_SNAPSHOT, DEATH_SNAPSHOT, LOST_SNAPSHOT, COOP_SNAPSHOT ->
                        activeKinds.add(observation.kind());
                default -> {
                }
            }
        }
        return activeKinds.size() > 1;
    }

    @Nonnull
    private static CompanionPopulationEvidence.Kind resolveLifecycleKind(
            @Nonnull List<CompanionPopulationEvidence> observations,
            boolean physical
    ) {
        if (physical) {
            for (CompanionPopulationEvidence observation : observations) {
                if (observation.kind() == CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY) {
                    return CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY;
                }
            }
            return CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY;
        }
        CompanionPopulationEvidence.Kind[] priority = {
                CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT,
                CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT,
                CompanionPopulationEvidence.Kind.LOST_SNAPSHOT,
                CompanionPopulationEvidence.Kind.COOP_SNAPSHOT,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                CompanionPopulationEvidence.Kind.PROFILE_RECORD
        };
        for (CompanionPopulationEvidence.Kind kind : priority) {
            for (CompanionPopulationEvidence observation : observations) {
                if (observation.kind() == kind) {
                    return kind;
                }
            }
        }
        throw new IllegalStateException("No lifecycle evidence was resolved.");
    }

    @Nullable
    private static String firstOwnershipWorld(@Nonnull List<CompanionPopulationEvidence> observations) {
        for (CompanionPopulationEvidence evidence : observations) {
            if (evidence.ownershipWorldName() != null && !evidence.ownershipWorldName().isBlank()) {
                return evidence.ownershipWorldName().trim();
            }
        }
        return null;
    }

    public record ResolvedEvidence(@Nonnull UUID npcUuid,
                                   @Nullable UUID observedOwnerUuid,
                                   boolean ownerObserved,
                                   boolean physical,
                                   boolean deathObserved,
                                   @Nonnull CompanionPopulationEvidence.Kind lifecycleKind,
                                   @Nullable String ownershipWorldName,
                                   @Nullable PhysicalLocation physicalLocation,
                                   int observationCount,
                                   @Nonnull Set<String> sources) {
        /** A corpse is a physical representation, but it is not live claim occupancy. */
        public boolean livePhysical() {
            return physical && !deathObserved;
        }
    }

    public record PhysicalLocation(@Nonnull String worldName, int chunkX, int chunkZ) {
        public PhysicalLocation {
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("Physical world name must not be blank.");
            }
            worldName = worldName.trim();
        }
    }

    /** Projection-only observation retained outside ordinary by-UUID repair selection. */
    public record ProjectionObservation(
            @Nonnull CompanionPopulationEvidence evidence,
            @Nonnull String fingerprint,
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid,
            boolean deathObserved
    ) {
        public ProjectionObservation {
            Objects.requireNonNull(evidence, "evidence");
            CompanionProjectionEvidence.ProjectionObservation parsed =
                    Objects.requireNonNull(evidence.projectionObservation(), "projection evidence");
            if (!Objects.equals(parsed.fingerprint(), fingerprint)
                    || !Objects.equals(parsed.componentUuid(), componentUuid)
                    || !Objects.equals(parsed.legacyNpcUuid(), legacyNpcUuid)
                    || parsed.deathObserved() != deathObserved) {
                throw new IllegalArgumentException(
                        "Projection observation must match its encoded evidence key."
                );
            }
        }

        /** Compatibility constructor for version-one live projection evidence. */
        public ProjectionObservation(
                @Nonnull CompanionPopulationEvidence evidence,
                @Nonnull String fingerprint,
                @Nullable UUID componentUuid,
                @Nullable UUID legacyNpcUuid
        ) {
            this(evidence, fingerprint, componentUuid, legacyNpcUuid, false);
        }
    }

    public record Conflict(@Nonnull UUID npcUuid,
                           @Nonnull String reason,
                           @Nonnull List<CompanionPopulationEvidence> evidence) {
        public Conflict {
            evidence = List.copyOf(evidence);
        }
    }

    private record Resolution(@Nullable ResolvedEvidence evidence, @Nullable Conflict conflict) {
        @Nonnull
        private static Resolution conflict(@Nonnull UUID npcUuid,
                                           @Nonnull String reason,
                                           @Nonnull List<CompanionPopulationEvidence> evidence) {
            return new Resolution(null, new Conflict(npcUuid, reason, evidence));
        }
    }
}
