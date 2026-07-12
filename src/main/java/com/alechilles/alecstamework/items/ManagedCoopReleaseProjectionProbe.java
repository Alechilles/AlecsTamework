package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.LoadedNpcObservation;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Probe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProbeStatus;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionKey;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionProbe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionProbeStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityRequest;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Combines the loaded-UUID and projection-marker indexes with an exact owning-store ECS read.
 *
 * <p>A release is absent only when both complete indexes prove absence. A live projection is
 * adoptable only when its deterministic UUID, immutable marker observation, location, and current
 * ECS marker all agree. Any alternate UUID or duplicate marker remains ambiguous so recovery
 * retains the durable release claim instead of spawning another NPC.</p>
 */
final class ManagedCoopReleaseProjectionProbe {
    enum Outcome {
        PRESENT,
        ABSENT,
        AMBIGUOUS
    }

    enum ProjectionReadStatus {
        FOUND,
        CONFLICT,
        UNAVAILABLE
    }

    record Result(@Nonnull Outcome outcome,
                  @Nullable UUID observedUuid,
                  @Nullable String detail) {
        Result {
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Outcome.PRESENT) != (observedUuid != null)) {
                throw new IllegalArgumentException(
                        "only a present projection may expose an observed UUID");
            }
        }

        static Result present(UUID observedUuid) {
            return new Result(Outcome.PRESENT,
                    Objects.requireNonNull(observedUuid, "observedUuid"), null);
        }

        static Result absent() {
            return new Result(Outcome.ABSENT, null, null);
        }

        static Result ambiguous(String detail) {
            return new Result(Outcome.AMBIGUOUS, null, requireText(detail, "detail"));
        }
    }

    record ProjectionRead(@Nonnull ProjectionReadStatus status,
                          @Nullable TameworkProjectionIdentityComponent marker,
                          @Nullable String detail) {
        ProjectionRead {
            Objects.requireNonNull(status, "status");
        }

        static ProjectionRead found(TameworkProjectionIdentityComponent marker) {
            return new ProjectionRead(ProjectionReadStatus.FOUND,
                    Objects.requireNonNull(marker, "marker"), null);
        }

        static ProjectionRead conflict(String detail) {
            return new ProjectionRead(ProjectionReadStatus.CONFLICT, null, detail);
        }

        static ProjectionRead unavailable(String detail) {
            return new ProjectionRead(ProjectionReadStatus.UNAVAILABLE, null, detail);
        }
    }

    @FunctionalInterface
    interface LoadedIdentityGateway {
        @Nonnull
        Probe probe(@Nonnull UUID npcUuid);
    }

    @FunctionalInterface
    interface ProjectionIndexGateway {
        @Nonnull
        ProjectionProbe probe(@Nonnull ProjectionKey key);
    }

    @FunctionalInterface
    interface ExactProjectionReader {
        @Nonnull
        ProjectionRead read(@Nonnull Store<EntityStore> owningStore,
                            @Nonnull UUID plannedTargetUuid,
                            @Nonnull Location indexedLocation);
    }

    private final LoadedIdentityGateway loadedIdentities;
    private final ProjectionIndexGateway projections;
    private final ExactProjectionReader exactReader;

    ManagedCoopReleaseProjectionProbe(@Nonnull LoadedNpcIdentityIndex identities) {
        this(
                Objects.requireNonNull(identities, "identities")::probe,
                identities::probeProjection,
                new HytaleExactProjectionReader()
        );
    }

    ManagedCoopReleaseProjectionProbe(@Nonnull LoadedIdentityGateway loadedIdentities,
                                      @Nonnull ProjectionIndexGateway projections,
                                      @Nonnull ExactProjectionReader exactReader) {
        this.loadedIdentities = Objects.requireNonNull(loadedIdentities, "loadedIdentities");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.exactReader = Objects.requireNonNull(exactReader, "exactReader");
    }

    @Nonnull
    Result probe(@Nonnull LiveIdentityRequest request,
                 @Nonnull Store<EntityStore> owningStore) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(owningStore, "owningStore");
        try {
            owningStore.assertThread();
            ProjectionKey key = projectionKey(request);
            Probe planned = trustedPlannedProbe(request.plannedTargetUuid());
            ProjectionProbe markers = trustedProjectionProbe(key);
            if (planned == null || markers == null) {
                return Result.ambiguous("release_projection_index_evidence_malformed");
            }

            Location owningLocation = LoadedNpcLocationResolver.resolve(owningStore);
            boolean plannedInOwningStore = planned.status() == ProbeStatus.ONE_LOCATION
                    && owningLocation.equals(planned.locations().getFirst());
            MatchCounts counts = countMatches(
                    markers.matches(), request.plannedTargetUuid(), owningLocation);
            boolean exactMarkerMatches = false;
            String exactReadDetail = null;
            if (plannedInOwningStore) {
                ProjectionRead read = exactReader.read(
                        owningStore, request.plannedTargetUuid(),
                        planned.locations().getFirst());
                if (read == null || read.status() == null) {
                    return Result.ambiguous("release_projection_exact_read_missing");
                }
                exactMarkerMatches = read.status() == ProjectionReadStatus.FOUND
                        && markerMatches(request, read.marker());
                exactReadDetail = read.detail();
            }

            Outcome outcome = classify(
                    planned.status(), plannedInOwningStore, markers.status(),
                    exactMarkerMatches, counts.exact(), counts.alternate());
            return switch (outcome) {
                case PRESENT -> Result.present(request.plannedTargetUuid());
                case ABSENT -> Result.absent();
                case AMBIGUOUS -> Result.ambiguous(ambiguityDetail(
                        planned, markers, plannedInOwningStore, counts,
                        exactMarkerMatches, exactReadDetail));
            };
        } catch (RuntimeException exception) {
            return Result.ambiguous(
                    "release_projection_probe_failed:" + exceptionDetail(exception));
        }
    }

    static Outcome classify(ProbeStatus plannedStatus,
                            boolean plannedInOwningStore,
                            ProjectionProbeStatus markerStatus,
                            boolean exactMarkerMatches,
                            int exactMatches,
                            int alternateMatches) {
        if (plannedStatus == ProbeStatus.ABSENT
                && markerStatus == ProjectionProbeStatus.ABSENT
                && exactMatches == 0 && alternateMatches == 0) {
            return Outcome.ABSENT;
        }
        if (plannedStatus == ProbeStatus.ONE_LOCATION
                && plannedInOwningStore
                && markerStatus == ProjectionProbeStatus.ONE_MATCH
                && exactMarkerMatches
                && exactMatches == 1 && alternateMatches == 0) {
            return Outcome.PRESENT;
        }
        return Outcome.AMBIGUOUS;
    }

    @Nonnull
    static ProjectionKey projectionKey(@Nonnull LiveIdentityRequest request) {
        Objects.requireNonNull(request, "request");
        return new ProjectionKey(
                request.profileId(), request.operationId(), request.projectionKind(),
                request.authoritySlotKey(), request.sourceNpcUuid(),
                request.operationGeneration());
    }

    @Nullable
    private Probe trustedPlannedProbe(UUID plannedTargetUuid) {
        Probe probe = loadedIdentities.probe(plannedTargetUuid);
        if (probe == null || probe.status() == null
                || !Objects.equals(plannedTargetUuid, probe.npcUuid())) {
            return null;
        }
        int locations = probe.locations().size();
        boolean shapeMatches = switch (probe.status()) {
            case UNKNOWN, ABSENT -> locations == 0;
            case ONE_LOCATION -> locations == 1;
            case MULTIPLE_LOCATIONS -> locations > 1;
        };
        return shapeMatches ? probe : null;
    }

    @Nullable
    private ProjectionProbe trustedProjectionProbe(ProjectionKey expectedKey) {
        ProjectionProbe probe = projections.probe(expectedKey);
        if (probe == null || probe.status() == null
                || !Objects.equals(expectedKey, probe.key())) {
            return null;
        }
        List<LoadedNpcObservation> matches = probe.matches();
        boolean shapeMatches = switch (probe.status()) {
            case UNKNOWN, ABSENT -> matches.isEmpty();
            case ONE_MATCH -> matches.size() == 1;
            case MULTIPLE_MATCHES -> matches.size() > 1;
        };
        if (!shapeMatches) {
            return null;
        }
        for (LoadedNpcObservation match : matches) {
            if (match == null || !Objects.equals(expectedKey, match.projectionKey())) {
                return null;
            }
        }
        return probe;
    }

    private MatchCounts countMatches(List<LoadedNpcObservation> matches,
                                     UUID plannedTargetUuid,
                                     Location owningLocation) {
        int exact = 0;
        int alternate = 0;
        for (LoadedNpcObservation match : matches) {
            boolean plannedIdentity = Objects.equals(plannedTargetUuid, match.componentUuid())
                    && Objects.equals(plannedTargetUuid, match.legacyUuid())
                    && Objects.equals(owningLocation, match.location());
            if (plannedIdentity) {
                exact++;
            } else {
                alternate++;
            }
        }
        return new MatchCounts(exact, alternate);
    }

    private String ambiguityDetail(Probe planned,
                                   ProjectionProbe markers,
                                   boolean plannedInOwningStore,
                                   MatchCounts counts,
                                   boolean exactMarkerMatches,
                                   @Nullable String exactReadDetail) {
        if (counts.alternate() > 0) {
            return "release_projection_marker_found_at_unexpected_identity";
        }
        if (markers.status() == ProjectionProbeStatus.UNKNOWN
                || planned.status() == ProbeStatus.UNKNOWN) {
            return "release_projection_index_evidence_untrusted";
        }
        if (planned.status() == ProbeStatus.MULTIPLE_LOCATIONS
                || markers.status() == ProjectionProbeStatus.MULTIPLE_MATCHES) {
            return "release_projection_identity_evidence_duplicated";
        }
        if (planned.status() == ProbeStatus.ONE_LOCATION && !plannedInOwningStore) {
            return "release_projection_loaded_in_other_store";
        }
        if (plannedInOwningStore && !exactMarkerMatches) {
            return exactReadDetail == null || exactReadDetail.isBlank()
                    ? "release_projection_exact_marker_conflict" : exactReadDetail;
        }
        return "release_projection_identity_evidence_conflict";
    }

    private boolean markerMatches(LiveIdentityRequest request,
                                  @Nullable TameworkProjectionIdentityComponent marker) {
        return marker != null
                && Objects.equals(request.operationId(), marker.getOperationId())
                && Objects.equals(request.profileId(), marker.getProfileId())
                && Objects.equals(request.projectionKind(), marker.getProjectionKind())
                && Objects.equals(request.authoritySlotKey(), marker.getSlotKey())
                && Objects.equals(request.sourceNpcUuid(), marker.getSourceNpcUuid())
                && request.operationGeneration() == marker.getGeneration();
    }

    private record MatchCounts(int exact, int alternate) {
        private MatchCounts {
            if (exact < 0 || alternate < 0) {
                throw new IllegalArgumentException("marker match counts cannot be negative");
            }
        }
    }

    private static final class HytaleExactProjectionReader implements ExactProjectionReader {
        @Override
        public ProjectionRead read(Store<EntityStore> owningStore,
                                   UUID plannedTargetUuid,
                                   Location indexedLocation) {
            Location owningLocation = LoadedNpcLocationResolver.resolve(owningStore);
            if (!owningLocation.equals(indexedLocation)) {
                return ProjectionRead.conflict("planned_uuid_loaded_in_other_store");
            }
            owningStore.assertThread();
            World world = owningStore.getExternalData() != null
                    ? owningStore.getExternalData().getWorld() : null;
            if (world == null) {
                return ProjectionRead.unavailable("owning_world_unavailable");
            }
            Ref<EntityStore> reference = world.getEntityRef(plannedTargetUuid);
            if (reference == null || !reference.isValid()) {
                return ProjectionRead.unavailable("planned_projection_not_resolvable");
            }
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                    TameworkProjectionIdentityComponent.getComponentType();
            if (type == null) {
                return ProjectionRead.unavailable("projection_marker_type_unavailable");
            }
            TameworkProjectionIdentityComponent marker = owningStore.getComponent(reference, type);
            return marker != null
                    ? ProjectionRead.found(marker.clone())
                    : ProjectionRead.conflict("planned_projection_marker_missing");
        }
    }

    private static String exceptionDetail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
