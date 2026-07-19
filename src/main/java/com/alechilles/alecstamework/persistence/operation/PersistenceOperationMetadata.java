package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable write-queue metadata used for isolation, incident scope, and commit read-back. */
public record PersistenceOperationMetadata(
        @Nonnull String taskName,
        @Nullable String traceId,
        @Nullable String operationId,
        @Nonnull String atomicGroupId,
        @Nonnull PersistenceDomain domain,
        @Nonnull PersistenceOperationPhase phase,
        @Nonnull List<PersistenceScope> scopes,
        @Nonnull PersistenceReadbackStrategy readbackStrategy,
        boolean durableFenceAvailable,
        boolean canonicalStateReadable,
        boolean liveMutationMayBeVisible,
        boolean sourceRetained,
        boolean legacySubmission) {

    public PersistenceOperationMetadata {
        taskName = requireText(taskName, "taskName");
        atomicGroupId = requireText(atomicGroupId, "atomicGroupId");
        if (domain == null || phase == null || readbackStrategy == null) {
            throw new IllegalArgumentException("domain, phase, and readbackStrategy are required");
        }
        traceId = normalize(traceId);
        operationId = normalize(operationId);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    @Nonnull
    public static PersistenceOperationMetadata legacy(@Nonnull String taskName) {
        return new PersistenceOperationMetadata(
                taskName, null, null, "legacy-" + UUID.randomUUID(),
                PersistenceDomain.STORAGE, PersistenceOperationPhase.APPLYING,
                List.of(), PersistenceReadbackStrategy.NONE,
                false, false, false, false, true);
    }

    @Nonnull
    public static Builder builder(@Nonnull String taskName, @Nonnull PersistenceDomain domain) {
        return new Builder(taskName, domain);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        return value.trim();
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Focused builder for repository call sites that progressively acquire correlation evidence. */
    public static final class Builder {
        private final String taskName;
        private final PersistenceDomain domain;
        private String traceId;
        private String operationId;
        private String atomicGroupId = UUID.randomUUID().toString();
        private PersistenceOperationPhase phase = PersistenceOperationPhase.APPLYING;
        private List<PersistenceScope> scopes = List.of();
        private PersistenceReadbackStrategy readbackStrategy = PersistenceReadbackStrategy.NONE;
        private boolean durableFenceAvailable;
        private boolean canonicalStateReadable;
        private boolean liveMutationMayBeVisible;
        private boolean sourceRetained;

        private Builder(String taskName, PersistenceDomain domain) {
            this.taskName = taskName;
            this.domain = domain;
        }

        public Builder traceId(String value) { traceId = value; return this; }
        public Builder operationId(String value) { operationId = value; return this; }
        public Builder atomicGroupId(String value) { atomicGroupId = value; return this; }
        public Builder phase(PersistenceOperationPhase value) { phase = value; return this; }
        public Builder scopes(List<PersistenceScope> value) { scopes = value; return this; }
        public Builder readbackStrategy(PersistenceReadbackStrategy value) {
            readbackStrategy = value; return this;
        }
        public Builder durableFenceAvailable(boolean value) { durableFenceAvailable = value; return this; }
        public Builder canonicalStateReadable(boolean value) { canonicalStateReadable = value; return this; }
        public Builder liveMutationMayBeVisible(boolean value) { liveMutationMayBeVisible = value; return this; }
        public Builder sourceRetained(boolean value) { sourceRetained = value; return this; }

        @Nonnull
        public PersistenceOperationMetadata build() {
            return new PersistenceOperationMetadata(
                    taskName, traceId, operationId, atomicGroupId, domain, phase, scopes,
                    readbackStrategy, durableFenceAvailable, canonicalStateReadable,
                    liveMutationMayBeVisible, sourceRetained, false);
        }
    }
}
