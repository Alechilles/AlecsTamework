package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Probe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProbeStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionProbe.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseProjectionProbe.Result;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityDecision;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityGuard;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.component.Store;
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
 * <p>All cross-world evidence comes from immutable trusted indexes. The exact planned UUID is
 * read synchronously in the caller's owning store only after both the UUID and projection-marker
 * indexes agree that it is the sole release projection.</p>
 */
public final class ManagedCoopReleaseLiveIdentityGuard implements LiveIdentityGuard {
    enum EvidenceStatus {
        TRUSTED,
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
    interface ProjectionProbeGateway {
        @Nonnull
        Result probe(@Nonnull LiveIdentityRequest request,
                     @Nonnull Store<EntityStore> owningStore);
    }

    private final AliasEvidenceGateway aliasEvidence;
    private final LoadedIdentityGateway loadedIdentities;
    private final ProjectionProbeGateway projectionProbe;

    public ManagedCoopReleaseLiveIdentityGuard(
            @Nonnull LoadedNpcIdentityIndex loadedIdentityIndex,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull BooleanSupplier compositeTrust) {
        this(
                new ResidentIndexAliasEvidence(
                        Objects.requireNonNull(residentIndex, "residentIndex"),
                        Objects.requireNonNull(compositeTrust, "compositeTrust")),
                Objects.requireNonNull(loadedIdentityIndex, "loadedIdentityIndex")::probe,
                new ManagedCoopReleaseProjectionProbe(loadedIdentityIndex)::probe
        );
    }

    ManagedCoopReleaseLiveIdentityGuard(
            @Nonnull AliasEvidenceGateway aliasEvidence,
            @Nonnull LoadedIdentityGateway loadedIdentities,
            @Nonnull ProjectionProbeGateway projectionProbe) {
        this.aliasEvidence = Objects.requireNonNull(aliasEvidence, "aliasEvidence");
        this.loadedIdentities = Objects.requireNonNull(loadedIdentities, "loadedIdentities");
        this.projectionProbe = Objects.requireNonNull(projectionProbe, "projectionProbe");
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
            Result projection = projectionProbe.probe(request, owningStore);
            if (projection == null || projection.outcome() == null) {
                return LiveIdentityDecision.lookupFailed("release_projection_probe_missing");
            }
            if (projection.outcome() == Outcome.PRESENT) {
                return LiveIdentityDecision.matching(projection.observedUuid());
            }
            if (projection.outcome() == Outcome.ABSENT) {
                return LiveIdentityDecision.clearToSpawn();
            }
            return LiveIdentityDecision.lookupFailed(detail(
                    projection.detail(), "release_projection_identity_ambiguous"));
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
