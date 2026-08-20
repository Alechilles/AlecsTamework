package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Internal aggregate admission request for one stable managed litter operation. */
public record ManagedBatchAdmissionRequest(
        @Nonnull UUID litterOperationId,
        @Nonnull PopulationAdmissionRequestV3 admission,
        int requestedUnits,
        @Nonnull List<UUID> provisionalChildIds
) {
    public ManagedBatchAdmissionRequest {
        litterOperationId = Objects.requireNonNull(litterOperationId, "litterOperationId");
        admission = Objects.requireNonNull(admission, "admission");
        if (requestedUnits <= 0) {
            throw new IllegalArgumentException("requestedUnits must be positive");
        }
        if (provisionalChildIds == null || provisionalChildIds.size() != requestedUnits
                || provisionalChildIds.stream().anyMatch(Objects::isNull)
                || provisionalChildIds.stream().distinct().count() != requestedUnits) {
            throw new IllegalArgumentException(
                    "One deterministic provisional identity is required per requested unit"
            );
        }
        for (int ordinal = 0; ordinal < requestedUnits; ordinal++) {
            UUID expected = deterministicChild(litterOperationId, ordinal);
            if (!expected.equals(provisionalChildIds.get(ordinal))) {
                throw new IllegalArgumentException(
                        "Provisional child identities must be deterministic"
                );
            }
        }
        provisionalChildIds = List.copyOf(provisionalChildIds);
    }

    /** Creates deterministic child identities from one stable litter operation ID. */
    @Nonnull
    public static ManagedBatchAdmissionRequest create(
            @Nonnull UUID litterOperationId,
            @Nonnull PopulationAdmissionRequestV3 admission,
            int requestedUnits
    ) {
        if (litterOperationId == null || admission == null || requestedUnits <= 0) {
            throw new IllegalArgumentException("Complete managed batch request is required");
        }
        ArrayList<UUID> children = new ArrayList<>(requestedUnits);
        for (int ordinal = 0; ordinal < requestedUnits; ordinal++) {
            children.add(deterministicChild(litterOperationId, ordinal));
        }
        return new ManagedBatchAdmissionRequest(
                litterOperationId,
                admission,
                requestedUnits,
                children
        );
    }

    private static UUID deterministicChild(UUID litterOperationId, int ordinal) {
        return UUID.nameUUIDFromBytes(
                (litterOperationId + ":child:" + ordinal)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }
}
