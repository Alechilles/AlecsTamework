package com.alechilles.alecstamework.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Ordered, identity-complete breeding batch; every unit represents exactly one child profile. */
public record PopulationBatchAdmissionRequest(@Nonnull String batchIdempotencyKey,
                                              @Nonnull List<PopulationAdmissionRequest> units,
                                              @Nonnull PopulationBatchAdmissionMode mode) {
    public static final int MAX_UNITS = 256;

    public PopulationBatchAdmissionRequest {
        batchIdempotencyKey = requireText(batchIdempotencyKey, "batchIdempotencyKey");
        mode = Objects.requireNonNull(mode, "mode");
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("A population admission batch requires at least one unit.");
        }
        if (units.size() > MAX_UNITS) {
            throw new IllegalArgumentException("Population admission batch exceeds " + MAX_UNITS + " units.");
        }
        units = List.copyOf(units);
        validateUnits(units);
    }

    private static void validateUnits(List<PopulationAdmissionRequest> units) {
        Set<String> identities = new HashSet<>();
        Set<UUID> npcUuids = new HashSet<>();
        Set<String> idempotencyKeys = new HashSet<>();
        for (PopulationAdmissionRequest unit : units) {
            Objects.requireNonNull(unit, "Population admission batch cannot contain null units.");
            if (unit.operation() != PopulationAdmissionOperation.BREEDING) {
                throw new IllegalArgumentException("Population admission batches currently require BREEDING units.");
            }
            String identity = identityKey(unit.identity());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Population admission batch contains duplicate profile identity.");
            }
            if (unit.currentNpcUuid() != null && !npcUuids.add(unit.currentNpcUuid())) {
                throw new IllegalArgumentException("Population admission batch contains duplicate current NPC UUID.");
            }
            String idempotencyKey = unit.identity().idempotencyKey();
            if (idempotencyKey != null && !idempotencyKeys.add(idempotencyKey)) {
                throw new IllegalArgumentException("Population admission batch contains duplicate unit idempotency key.");
            }
        }
    }

    @Nonnull
    private static String identityKey(PopulationAdmissionIdentity identity) {
        if (identity.canonicalProfileId() != null) {
            return "canonical:" + identity.canonicalProfileId();
        }
        if (identity.provisionalProfileId() != null) {
            return "provisional:" + identity.provisionalProfileId();
        }
        return "idempotency:" + identity.idempotencyKey();
    }

    @Nonnull
    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank.");
        }
        return normalized;
    }
}
