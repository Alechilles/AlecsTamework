package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Probe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProbeStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityDecision;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityGuard;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityRequest;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fail-closed live UUID, profile-alias, and projection-marker guard for managed-coop release.
 *
 * <p>All durable evidence comes from immutable trusted indexes. The only ECS read is the exact
 * planned UUID in the caller's owning store, performed synchronously after the loaded-identity
 * index proves that UUID has exactly one current location.</p>
 */
public final class ManagedCoopReleaseLiveIdentityGuard implements LiveIdentityGuard {
    enum EvidenceStatus {
        TRUSTED,
        CONFLICT,
        UNAVAILABLE
    }

    enum ProjectionStatus {
        FOUND,
        CONFLICT,
        UNAVAILABLE
    }

    record AliasEvidence(@Nonnull EvidenceStatus status,
                         @Nonnull List<UUID> aliases,
                         @Nullable String detail) {
        AliasEvidence {
            Objects.requireNonNull(status, "status");
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        }

        static AliasEvidence trusted(List<UUID> aliases) {
            return new AliasEvidence(EvidenceStatus.TRUSTED, aliases, null);
        }

        static AliasEvidence conflict(String detail) {
            return new AliasEvidence(EvidenceStatus.CONFLICT, List.of(), detail);
        }

        static AliasEvidence unavailable(String detail) {
            return new AliasEvidence(EvidenceStatus.UNAVAILABLE, List.of(), detail);
        }
    }

    record ProjectionRead(@Nonnull ProjectionStatus status,
                          @Nullable TameworkProjectionIdentityComponent marker,
                          @Nullable String detail) {
        static ProjectionRead found(TameworkProjectionIdentityComponent marker) {
            return new ProjectionRead(ProjectionStatus.FOUND, marker, null);
        }

        static ProjectionRead conflict(String detail) {
            return new ProjectionRead(ProjectionStatus.CONFLICT, null, detail);
        }

        static ProjectionRead unavailable(String detail) {
            return new ProjectionRead(ProjectionStatus.UNAVAILABLE, null, detail);
        }
    }

    @FunctionalInterface
    interface AliasEvidenceGateway {
        @Nonnull
        AliasEvidence evidence(@Nonnull LiveIdentityRequest request);
    }

    @FunctionalInterface
    interface LoadedIdentityGateway {
        @Nonnull
        Probe probe(@Nonnull UUID npcUuid);
    }

    @FunctionalInterface
    interface ProjectionReader {
        @Nonnull
        ProjectionRead read(@Nonnull Store<EntityStore> owningStore,
                            @Nonnull UUID plannedTargetUuid,
                            @Nonnull Location indexedLocation);
    }

    private final AliasEvidenceGateway aliasEvidence;
    private final LoadedIdentityGateway loadedIdentities;
    private final ProjectionReader projectionReader;

    public ManagedCoopReleaseLiveIdentityGuard(
            @Nonnull LoadedNpcIdentityIndex loadedIdentityIndex,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull BooleanSupplier compositeTrust) {
        this(
                new ResidentIndexAliasEvidence(
                        Objects.requireNonNull(residentIndex, "residentIndex"),
                        Objects.requireNonNull(compositeTrust, "compositeTrust")),
                Objects.requireNonNull(loadedIdentityIndex, "loadedIdentityIndex")::probe,
                new HytaleProjectionReader()
        );
    }

    ManagedCoopReleaseLiveIdentityGuard(
            @Nonnull AliasEvidenceGateway aliasEvidence,
            @Nonnull LoadedIdentityGateway loadedIdentities,
            @Nonnull ProjectionReader projectionReader) {
        this.aliasEvidence = Objects.requireNonNull(aliasEvidence, "aliasEvidence");
        this.loadedIdentities = Objects.requireNonNull(loadedIdentities, "loadedIdentities");
        this.projectionReader = Objects.requireNonNull(projectionReader, "projectionReader");
    }

    @Override
    @Nonnull
    public LiveIdentityDecision inspect(@Nonnull LiveIdentityRequest request,
                                        @Nonnull Store<EntityStore> owningStore) {
        if (request == null || owningStore == null) {
            return LiveIdentityDecision.lookupFailed("release_live_identity_input_missing");
        }
        try {
            owningStore.assertThread();
            AliasEvidence aliases = aliasEvidence.evidence(request);
            if (aliases == null || aliases.status() == null) {
                return LiveIdentityDecision.lookupFailed("profile_alias_evidence_missing");
            }
            if (aliases.status() == EvidenceStatus.UNAVAILABLE) {
                return LiveIdentityDecision.lookupFailed(detail(
                        aliases.detail(), "profile_alias_evidence_unavailable"));
            }
            if (aliases.status() == EvidenceStatus.CONFLICT) {
                return LiveIdentityDecision.conflict(detail(
                        aliases.detail(), "profile_alias_evidence_conflict"));
            }
            if (!aliases.aliases().contains(request.sourceNpcUuid())) {
                return LiveIdentityDecision.lookupFailed(
                        "profile_alias_evidence_missing_source_uuid");
            }

            LiveIdentityDecision aliasDecision = inspectOtherAliases(request, aliases.aliases());
            if (aliasDecision != null) {
                return aliasDecision;
            }
            Probe planned = trustedProbe(request.plannedTargetUuid());
            if (planned == null) {
                return LiveIdentityDecision.lookupFailed("planned_uuid_probe_untrusted");
            }
            return inspectPlanned(request, owningStore, planned);
        } catch (RuntimeException exception) {
            return LiveIdentityDecision.lookupFailed(
                    "release_live_identity_lookup_failed:" + exceptionDetail(exception));
        }
    }

    @Nullable
    private LiveIdentityDecision inspectOtherAliases(LiveIdentityRequest request,
                                                     List<UUID> aliases) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(aliases);
        unique.remove(null);
        unique.remove(request.plannedTargetUuid());
        for (UUID alias : unique) {
            Probe probe = trustedProbe(alias);
            if (probe == null || probe.status() == ProbeStatus.UNKNOWN) {
                return LiveIdentityDecision.lookupFailed(
                        "profile_alias_presence_untrusted:" + alias);
            }
            if (probe.status() != ProbeStatus.ABSENT) {
                return LiveIdentityDecision.conflict(
                        "conflicting_live_profile_alias:" + alias);
            }
        }
        return null;
    }

    @Nonnull
    private LiveIdentityDecision inspectPlanned(LiveIdentityRequest request,
                                                Store<EntityStore> owningStore,
                                                Probe planned) {
        if (planned.status() == ProbeStatus.UNKNOWN) {
            return LiveIdentityDecision.lookupFailed("planned_uuid_presence_untrusted");
        }
        if (planned.status() == ProbeStatus.ABSENT) {
            return LiveIdentityDecision.clearToSpawn();
        }
        if (planned.status() == ProbeStatus.MULTIPLE_LOCATIONS) {
            return LiveIdentityDecision.conflict("planned_uuid_loaded_in_multiple_locations");
        }
        ProjectionRead read = projectionReader.read(
                owningStore, request.plannedTargetUuid(), planned.locations().getFirst());
        if (read == null || read.status() == null) {
            return LiveIdentityDecision.lookupFailed("planned_projection_read_missing");
        }
        if (read.status() == ProjectionStatus.UNAVAILABLE) {
            return LiveIdentityDecision.lookupFailed(detail(
                    read.detail(), "planned_projection_unavailable"));
        }
        if (read.status() == ProjectionStatus.CONFLICT
                || !markerMatches(request, read.marker())) {
            return LiveIdentityDecision.conflict(detail(
                    read.detail(), "planned_projection_marker_conflict"));
        }
        return LiveIdentityDecision.matching(request.plannedTargetUuid());
    }

    @Nullable
    private Probe trustedProbe(UUID expectedUuid) {
        Probe probe = loadedIdentities.probe(expectedUuid);
        if (probe == null || probe.status() == null
                || !Objects.equals(expectedUuid, probe.npcUuid())) {
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

    private static final class ResidentIndexAliasEvidence implements AliasEvidenceGateway {
        private final ManagedCoopResidentIndex residents;
        private final BooleanSupplier compositeTrust;

        private ResidentIndexAliasEvidence(ManagedCoopResidentIndex residents,
                                           BooleanSupplier compositeTrust) {
            this.residents = residents;
            this.compositeTrust = compositeTrust;
        }

        @Override
        public AliasEvidence evidence(LiveIdentityRequest request) {
            if (!isTrusted()) {
                return AliasEvidence.unavailable("managed_coop_composite_index_untrusted");
            }
            ManagedCoopResidentIndex.Snapshot snapshot = residents.snapshot();
            ResidentRecord resident = snapshot.residentByProfile(request.profileId());
            if (!isTrusted()) {
                return AliasEvidence.unavailable("managed_coop_composite_trust_changed");
            }
            if (resident == null) {
                return AliasEvidence.unavailable("managed_coop_profile_not_indexed");
            }
            String expectedSlot = resident.authorityKey().slotKey(resident.residentSlot());
            if (!Objects.equals(request.authoritySlotKey(), expectedSlot)
                    || !Objects.equals(request.sourceNpcUuid(), resident.sourceNpcUuid())) {
                return AliasEvidence.conflict("managed_coop_profile_assignment_conflict");
            }
            if (!releaseStateMatches(request, resident)) {
                return AliasEvidence.conflict("managed_coop_profile_state_conflict");
            }
            LinkedHashSet<UUID> aliases = new LinkedHashSet<>();
            aliases.add(resident.residentUuid());
            aliases.add(resident.sourceNpcUuid());
            aliases.add(resident.deployedNpcUuid());
            aliases.remove(null);
            return AliasEvidence.trusted(List.copyOf(aliases));
        }

        private boolean isTrusted() {
            try {
                return compositeTrust.getAsBoolean() && residents.isTrusted();
            } catch (RuntimeException exception) {
                return false;
            }
        }

        private boolean releaseStateMatches(LiveIdentityRequest request, ResidentRecord resident) {
            if (!resident.active()) {
                return false;
            }
            if (resident.state() == ResidentState.RELEASING) {
                return Objects.equals(resident.residentUuid(), request.sourceNpcUuid())
                        && resident.deployedNpcUuid() == null;
            }
            return resident.state() == ResidentState.DEPLOYED
                    && Objects.equals(resident.residentUuid(), request.plannedTargetUuid())
                    && Objects.equals(resident.deployedNpcUuid(), request.plannedTargetUuid());
        }
    }

    private static final class HytaleProjectionReader implements ProjectionReader {
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

    @Nonnull
    private static String detail(@Nullable String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    @Nonnull
    private static String exceptionDetail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
