package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityView;
import com.alechilles.alecstamework.api.PersistenceScopeKind;
import com.alechilles.alecstamework.api.PersistenceScopeReference;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuitState;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceDiagnosticsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Exact read-only adapter from public availability requests to replacement
 * diagnostics and scoped containment evidence.
 */
public final class ReplacementPersistenceAvailabilityProbe
        implements ReplacementPersistenceDiagnosticsApi.AvailabilityProbe {
    private final PersistenceFeatureRegistry registry;
    private final Supplier<PublicPersistenceOperationalStatus> status;
    private final DiagnosticsLookup diagnostics;
    private final QuarantineLookup quarantines;
    private final FeatureScopeResolver featureScopes;
    private final long timeoutNanos;

    public ReplacementPersistenceAvailabilityProbe(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull PersistenceDiagnosticsReader diagnostics,
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull FeatureScopeResolver featureScopes,
            @Nonnull Duration timeout
    ) {
        this(
                registry,
                diagnostics::status,
                diagnostics::details,
                queries::findFirstActiveQuarantine,
                featureScopes,
                timeout
        );
    }

    public ReplacementPersistenceAvailabilityProbe(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull Supplier<PublicPersistenceOperationalStatus> status,
            @Nonnull DiagnosticsLookup diagnostics,
            @Nonnull QuarantineLookup quarantines,
            @Nonnull FeatureScopeResolver featureScopes,
            @Nonnull Duration timeout
    ) {
        if (registry == null || status == null || diagnostics == null
                || quarantines == null || featureScopes == null
                || timeout == null || timeout.isZero()
                || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Complete availability probe dependencies are required"
            );
        }
        this.registry = registry;
        this.status = status;
        this.diagnostics = diagnostics;
        this.quarantines = quarantines;
        this.featureScopes = featureScopes;
        timeoutNanos = timeout.toNanos();
    }

    @Override
    @Nonnull
    public PersistenceMutationAvailabilityView query(
            @Nonnull PersistenceMutationAvailabilityRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Persistence availability request is required"
            );
        }
        try {
            return queryExact(request);
        } catch (Exception unavailable) {
            return PersistenceMutationAvailabilityView.unavailable();
        }
    }

    private PersistenceMutationAvailabilityView queryExact(
            PersistenceMutationAvailabilityRequest request
    ) throws Exception {
        PublicPersistenceOperationalStatus operational = status.get();
        PersistenceMutationAvailabilityView storage =
                storageDenial(operational);
        if (storage != null) {
            return storage;
        }
        if (!request.requiredEvidenceDimensions().isEmpty()) {
            return deny(
                    "AUTHORITY_NOT_READY",
                    "replacement_evidence_dimension_unsupported",
                    null
            );
        }
        MappedRequest mapped = map(request);
        if (mapped == null) {
            return deny(
                    "AUTHORITY_NOT_READY",
                    "replacement_operation_scope_unsupported",
                    null
            );
        }
        long deadline = System.nanoTime() + timeoutNanos;
        PublicPersistenceDiagnosticsSnapshot snapshot =
                found(await(diagnostics.read(), deadline));
        if (snapshot == null) {
            return PersistenceMutationAvailabilityView.unavailable();
        }
        PersistenceReadResult<ScopeQuarantine> quarantineRead =
                await(quarantines.find(mapped.candidates()), deadline);
        return decide(snapshot, mapped, quarantineRead);
    }

    private PersistenceMutationAvailabilityView storageDenial(
            PublicPersistenceOperationalStatus operational
    ) {
        if (operational == null) {
            return PersistenceMutationAvailabilityView.unavailable();
        }
        if (operational.storageMode()
                == PublicPersistenceOperationalStatus.StorageMode.READ_WRITE) {
            return null;
        }
        return deny(
                "GLOBAL_READ_ONLY",
                "replacement_persistence_"
                        + operational.storageMode().name()
                        .toLowerCase(Locale.ROOT),
                null
        );
    }

    private PersistenceMutationAvailabilityView decide(
            PublicPersistenceDiagnosticsSnapshot snapshot,
            MappedRequest mapped,
            PersistenceReadResult<ScopeQuarantine> quarantineRead
    ) {
        if (quarantineRead instanceof PersistenceReadResult.Found<
                ScopeQuarantine> found) {
            ScopeQuarantine quarantine = found.value();
            return deny(
                    "QUARANTINED",
                    quarantine.reasonCode(),
                    quarantine.incidentId().toString()
            );
        }
        if (!(quarantineRead instanceof PersistenceReadResult.Absent<?>)) {
            return PersistenceMutationAvailabilityView.unavailable();
        }
        var feature = snapshot.features().get(
                mapped.descriptor().featureId()
        );
        if (feature == null) {
            return PersistenceMutationAvailabilityView.unavailable();
        }
        if (feature.circuit().state()
                != PersistenceFeatureCircuitState.CLOSED) {
            return deny(
                    "FEATURE_PAUSED",
                    Optional.ofNullable(feature.circuit().reasonCode())
                            .orElse("feature_paused_by_operator"),
                    null
            );
        }
        return readiness(feature.readiness());
    }

    private MappedRequest map(PersistenceMutationAvailabilityRequest request) {
        OperationKind kind;
        PersistenceFeatureDescriptor descriptor;
        String featureScope;
        try {
            kind = new OperationKind(request.operationKind());
            descriptor = registry.requireOperation(kind);
            featureScope = featureScopes.resolve(kind).orElse(null);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (featureScope == null || featureScope.isBlank()) {
            return null;
        }
        TreeSet<OperationScope> scopes = mapScopes(request.scopes());
        if (scopes == null
                || !addOperationScope(scopes, request.operationId())
                || !addFeatureScopes(scopes, featureScope)
                || !scopePolicyAdmits(descriptor, kind, scopes)) {
            return null;
        }
        return new MappedRequest(
                descriptor,
                List.copyOf(new ArrayList<>(scopes))
        );
    }

    private TreeSet<OperationScope> mapScopes(
            List<PersistenceScopeReference> references
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        for (PersistenceScopeReference reference : references) {
            OperationScope mapped = scope(reference);
            if (mapped == null || !scopes.add(mapped)) {
                return null;
            }
        }
        return scopes;
    }

    private boolean addOperationScope(
            TreeSet<OperationScope> scopes,
            String requestedOperationId
    ) {
        if (requestedOperationId != null) {
            OperationScope operation = OperationScope.operation(
                    OperationId.parse(requestedOperationId)
            );
            boolean conflicts = scopes.stream().anyMatch(scope ->
                    scope.type() == OperationScopeType.OPERATION
                            && !scope.equals(operation));
            if (conflicts) {
                return false;
            }
            scopes.add(operation);
        }
        return true;
    }

    private boolean addFeatureScopes(
            TreeSet<OperationScope> scopes,
            String featureScope
    ) {
        OperationScope feature = new OperationScope(
                OperationScopeType.FEATURE, featureScope
        );
        boolean featureMismatch = scopes.stream().anyMatch(scope ->
                scope.type() == OperationScopeType.FEATURE
                        && !scope.equals(feature));
        if (featureMismatch) {
            return false;
        }
        scopes.add(feature);
        scopes.add(OperationScope.global());
        return true;
    }

    private boolean scopePolicyAdmits(
            PersistenceFeatureDescriptor descriptor,
            OperationKind kind,
            TreeSet<OperationScope> scopes
    ) {
        Set<OperationScopeType> participantTypes = scopes.stream()
                .map(OperationScope::type)
                .filter(type -> type != OperationScopeType.OPERATION
                        && type != OperationScopeType.FEATURE
                        && type != OperationScopeType.GLOBAL)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return descriptor.operationScopes().get(kind).admits(
                participantTypes
        );
    }

    private OperationScope scope(PersistenceScopeReference reference) {
        if (reference == null) {
            return null;
        }
        try {
            return switch (reference.kind()) {
                case OPERATION -> OperationScope.operation(
                        OperationId.parse(reference.key())
                );
                case PROFILE -> OperationScope.profile(
                        ProfileId.parse(reference.key())
                );
                case OWNER_GLOBAL -> OperationScope.owner(
                        OwnerId.parse(reference.key())
                );
                case COOP_SLOT -> OperationScope.coop(
                        CoopSlotKey.parse(reference.key()).toString()
                );
                case COMMAND_FAMILY -> OperationScope.commandFamily(
                        reference.key()
                );
                case TOOL -> OperationScope.tool(reference.key());
                case FEATURE_DOMAIN -> new OperationScope(
                        OperationScopeType.FEATURE,
                        reference.key()
                );
                case GLOBAL -> "*".equals(reference.key())
                        ? OperationScope.global()
                        : null;
                case OWNER_WORLD, CLAIM, COOP_AUTHORITY,
                        BREEDING_ATTEMPT, BREEDING_PARENT, WORLD,
                        EVIDENCE_DIMENSION -> null;
            };
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private PersistenceMutationAvailabilityView readiness(
            PersistenceReadinessLevel readiness
    ) {
        return switch (readiness) {
            case MUTATION_READY -> new PersistenceMutationAvailabilityView(
                    "ALLOW", "allowed", null
            );
            case QUARANTINED -> deny(
                    "QUARANTINED",
                    "replacement_feature_quarantined",
                    null
            );
            case GLOBAL_READ_ONLY -> deny(
                    "GLOBAL_READ_ONLY",
                    "persistence_mutation_not_admitted:global_read_only",
                    null
            );
            case CLOSED, CANONICAL_READ_ONLY, RECOVERING,
                    PROJECTION_READY, WORLD_EVIDENCE_PENDING -> deny(
                    "AUTHORITY_NOT_READY",
                    "persistence_mutation_not_admitted:"
                            + readiness.name().toLowerCase(Locale.ROOT),
                    null
            );
        };
    }

    private PersistenceMutationAvailabilityView deny(
            String status,
            String reason,
            String incidentId
    ) {
        return new PersistenceMutationAvailabilityView(
                status, reason, incidentId
        );
    }

    private <T> PersistenceReadResult<T> await(
            CompletionStage<PersistenceReadResult<T>> stage,
            long deadline
    ) throws Exception {
        if (stage == null) {
            return null;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
        return stage.toCompletableFuture().get(
                remaining, TimeUnit.NANOSECONDS
        );
    }

    private <T> T found(PersistenceReadResult<T> read) {
        return read instanceof PersistenceReadResult.Found<T> found
                ? found.value()
                : null;
    }

    private record MappedRequest(
            PersistenceFeatureDescriptor descriptor,
            List<OperationScope> candidates
    ) {
    }

    /** Exact persisted feature scope for one registered operation kind. */
    @FunctionalInterface
    public interface FeatureScopeResolver {
        @Nonnull
        Optional<String> resolve(@Nonnull OperationKind operationKind);
    }

    /** Existing bounded replacement diagnostic reader. */
    @FunctionalInterface
    public interface DiagnosticsLookup {
        @Nonnull
        CompletionStage<PersistenceReadResult<
                PublicPersistenceDiagnosticsSnapshot>> read();
    }

    /** Existing exact-scope replacement quarantine reader. */
    @FunctionalInterface
    public interface QuarantineLookup {
        @Nonnull
        CompletionStage<PersistenceReadResult<ScopeQuarantine>> find(
                @Nonnull List<OperationScope> candidateScopes
        );
    }
}
