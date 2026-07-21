package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.google.gson.Gson;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact loaded-NPC marker and committed item-evidence adapter for vessel validation. */
public final class LoadedNpcBondedVesselProjectionEvidencePort
        implements ProductionBondedVesselEvidenceAuthority.ProjectionEvidencePort {
    private final LoadedNpcIdentityIndex loaded;
    private final Gson gson = new Gson();

    public LoadedNpcBondedVesselProjectionEvidencePort(
            @Nonnull LoadedNpcIdentityIndex loaded) {
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Nonnull
    @Override
    public ProductionBondedVesselEvidenceAuthority.ProjectionObservation observe(
            @Nonnull BondedVesselBindingRecord binding,
            @Nonnull BondedVesselProjectionValidationRequest request) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(request, "request");
        return request.projectionKind()
                == BondedVesselProjectionValidationRequest.ProjectionKind.ITEM
                ? item(binding) : live(binding);
    }

    @Nonnull
    @Override
    public ProductionBondedVesselEvidenceAuthority.PortReadiness readiness() {
        boolean ready = loaded.isInitializationComplete();
        return new ProductionBondedVesselEvidenceAuthority.PortReadiness(
                false, false, false, ready, ready
                ? "bonded-vessel-projection-evidence-ready"
                : "bonded-vessel-loaded-projection-scan-incomplete");
    }

    private ProductionBondedVesselEvidenceAuthority.ProjectionObservation item(
            BondedVesselBindingRecord binding) {
        return switch (binding.itemProjectionStatus()) {
            case MISSING -> observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.MISSING,
                    "bonded-vessel-item-projection-missing", binding, null);
            case AMBIGUOUS -> observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.DUPLICATE,
                    "bonded-vessel-item-projection-ambiguous", binding, null);
            case REISSUE_PENDING -> observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.PENDING,
                    "bonded-vessel-item-reissue-pending", binding, null);
            case QUARANTINED -> observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.QUARANTINED,
                    "bonded-vessel-item-projection-quarantined", binding, null);
            case PRESENT -> {
                BondedVesselSourceItemEvidence evidence = parseItemEvidence(
                        binding.itemEvidenceJson());
                yield evidence == null
                        ? observation(
                        ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.UNKNOWN,
                        "bonded-vessel-item-evidence-incomplete", binding, null)
                        : observation(
                        ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.EXACT,
                        "bonded-vessel-item-evidence-exact", binding,
                        evidence.itemFingerprint());
            }
        };
    }

    private ProductionBondedVesselEvidenceAuthority.ProjectionObservation live(
            BondedVesselBindingRecord binding) {
        UUID npcUuid = binding.activeNpcUuid();
        if (npcUuid == null) {
            return observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.MISSING,
                    "bonded-vessel-live-projection-not-recorded", binding, null);
        }
        LoadedNpcIdentityIndex.Probe probe = loaded.probe(npcUuid);
        if (probe.status() == LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN) {
            return observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.UNKNOWN,
                    "bonded-vessel-live-evidence-incomplete", binding, null);
        }
        if (probe.status() == LoadedNpcIdentityIndex.ProbeStatus.ABSENT) {
            return observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.MISSING,
                    "bonded-vessel-live-projection-missing", binding, null);
        }
        if (probe.status() == LoadedNpcIdentityIndex.ProbeStatus.MULTIPLE_LOCATIONS) {
            return observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.DUPLICATE,
                    "bonded-vessel-live-projection-duplicate", binding, null);
        }
        List<LoadedNpcIdentityIndex.LoadedNpcObservation> observations = loaded.snapshot()
                .observations().stream()
                .filter(candidate -> npcUuid.equals(candidate.componentUuid())
                        || npcUuid.equals(candidate.legacyNpcUuid()))
                .toList();
        if (observations.size() != 1) {
            return observation(observations.isEmpty()
                            ? ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.UNKNOWN
                            : ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.DUPLICATE,
                    observations.isEmpty() ? "bonded-vessel-live-marker-unavailable"
                            : "bonded-vessel-live-marker-duplicate", binding, null);
        }
        LoadedNpcIdentityIndex.ProjectionKey marker = observations.get(0).projectionKey();
        boolean exact = marker != null
                && binding.profileId().equals(marker.profileId())
                && TameworkProjectionIdentityComponent.KIND_BONDED_VESSEL.equals(
                        marker.projectionKind())
                && binding.bindingId().equals(marker.slotKey())
                && marker.generation() == binding.generation();
        if (!exact) {
            return observation(
                    ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.QUARANTINED,
                    "bonded-vessel-live-marker-mismatch", binding, null);
        }
        return observation(
                ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.EXACT,
                "bonded-vessel-live-projection-exact", binding,
                BondedVesselProjectionFingerprint.live(UUID.fromString(binding.bindingId()),
                        binding.profileId(), binding.generation(), npcUuid));
    }

    @Nullable
    private BondedVesselSourceItemEvidence parseItemEvidence(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            return gson.fromJson(encoded, BondedVesselSourceItemEvidence.class);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static ProductionBondedVesselEvidenceAuthority.ProjectionObservation observation(
            ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus status,
            String reason, BondedVesselBindingRecord binding, @Nullable String fingerprint) {
        UUID bindingId;
        try {
            bindingId = UUID.fromString(binding.bindingId());
        } catch (IllegalArgumentException invalid) {
            bindingId = null;
        }
        return new ProductionBondedVesselEvidenceAuthority.ProjectionObservation(
                status, reason, bindingId, binding.profileId(), binding.generation(), fingerprint);
    }
}
