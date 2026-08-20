package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PersistenceLifecycleAdmissionGateway;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Internal managed lifecycle authority composed from the existing admission authors. */
public final class ReplacementLifecycleAdmissionGateway
        implements PersistenceLifecycleAdmissionGateway {
    private final ManagedActivityConfigRegistry managed;
    private final ManagedAdmissionEvidenceAuthor evidenceAuthor;
    private final PopulationAdmissionCompositionAuthor compositionAuthor;

    public ReplacementLifecycleAdmissionGateway(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull ManagedActivityConfigRegistry managed,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nonnull AdmissionProviderRegistry providers,
            @Nonnull LongSupplier clock
    ) {
        Objects.requireNonNull(persistence, "persistence");
        this.managed = Objects.requireNonNull(managed, "managed");
        Objects.requireNonNull(populationGroups, "populationGroups");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(clock, "clock");
        evidenceAuthor = new ManagedAdmissionEvidenceAuthor(
                managed, populationGroups, providers, clock
        );
        compositionAuthor = new PopulationAdmissionCompositionAuthor(
                persistence, populationGroups
        );
    }

    @Override
    @Nonnull
    public CompletionStage<LifecycleAdmissionEvidence> authorize(
            @Nonnull LifecycleAdmissionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        ManagedActivityConfigRegistry.RoleResolution resolution = managed
                .resolveRole(request.targetRoleId())
                .orElse(null);
        if (resolution == null) {
            if (managed.snapshot().rolesById().containsKey(
                    request.targetRoleId()
            )) {
                return unavailable("managed-role-unavailable");
            }
            return CompletableFuture.completedFuture(
                    LifecycleAdmissionEvidence.unmanaged()
            );
        }
        if (request.managedRequest() == null) {
            return unavailable("managed-admission-request-required");
        }
        PopulationAdmissionRequestV3 resolvedRequest =
                new PopulationAdmissionRequestV3(
                        request.managedRequest(),
                        resolution.profile().profileId()
                );
        return evidenceAuthor.author(
                request.operationId(),
                request.reservationId(),
                resolvedRequest,
                request.sourceState(),
                request.targetState(),
                request.sourceOwner(),
                request.sourceWorld()
        ).thenCompose(authoring -> compositionAuthor.compose(
                resolvedRequest,
                request.source(),
                authoring.payload(),
                request.operationId()
        ).thenApply(composition -> result(authoring.payload(), composition)));
    }

    @Nonnull
    private LifecycleAdmissionEvidence result(
            @Nonnull PopulationDomainAdmissionOperation.Payload payload,
            PopulationAdmissionComposition composition
    ) {
        return payload.domains().isEmpty()
                ? LifecycleAdmissionEvidence.neutral()
                : LifecycleAdmissionEvidence.managed(payload, composition);
    }

    @Nonnull
    private <T> CompletionStage<T> unavailable(String detail) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(detail)
        );
    }
}
