package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Value-only context used to decide whether one exact persistence mutation may start. */
public record PersistenceMutationContext(@Nonnull PersistenceDomain domain,
                                         @Nonnull String operationKind,
                                         @Nonnull List<PersistenceScope> scopes,
                                         @Nonnull Set<String> requiredCoverageDimensions,
                                         @Nonnull PersistenceMutationDelta delta,
                                         @Nullable String traceId,
                                         @Nullable String operationId,
                                         boolean sourceMayExist,
                                         boolean liveProjectionMayExist) {
    public PersistenceMutationContext {
        if (domain == null || delta == null) throw new IllegalArgumentException("domain/delta");
        if (operationKind == null || operationKind.isBlank()) throw new IllegalArgumentException("operationKind");
        operationKind = operationKind.trim();
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        requiredCoverageDimensions = requiredCoverageDimensions == null
                ? Set.of() : Set.copyOf(requiredCoverageDimensions);
        traceId = normalize(traceId);
        operationId = normalize(operationId);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
