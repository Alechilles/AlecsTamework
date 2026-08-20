package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import com.alechilles.alecstamework.api.RequiredContentProfileApi;
import com.alechilles.alecstamework.api.RequiredContentProfileStatus;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Replacement-persistence facade for staged provider-aware admission. */
public final class ReplacementPopulationAdmissionApi
        implements PopulationAdmissionApi, RequiredContentProfileApi {
    private static final String UNAVAILABLE =
            "population-admission-authority-unavailable";

    private final PersistenceBootstrap persistence;
    private final ManagedAdmissionEvidenceAuthor author;
    private final ManagedActivityConfigRegistry managed;
    private final AdmissionProviderRegistry providers;
    private final PopulationAdmissionStaging staging;

    public ReplacementPopulationAdmissionApi(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull PublicPersistenceOperations publicOperations,
            @Nonnull ManagedActivityConfigRegistry managed,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nonnull AdmissionProviderRegistry providers,
            @Nonnull LongSupplier clock
    ) {
        this(
                persistence,
                Objects.requireNonNull(publicOperations, "publicOperations")
                        .populationDomainAdmission(),
                new ManagedAdmissionEvidenceAuthor(
                        managed, populationGroups, providers, clock
                ),
                managed,
                providers,
                clock
        );
    }

    public ReplacementPopulationAdmissionApi(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull PopulationDomainAdmissionOperation operations,
            @Nonnull ManagedAdmissionEvidenceAuthor author,
            @Nonnull ManagedActivityConfigRegistry managed,
            @Nonnull AdmissionProviderRegistry providers,
            @Nonnull LongSupplier clock
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.author = Objects.requireNonNull(author, "author");
        this.managed = Objects.requireNonNull(managed, "managed");
        this.providers = Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(clock, "clock");
        this.staging = new PopulationAdmissionStaging(operations);
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationAdmissionDecision> tryAdmit(
            @Nonnull PopulationAdmissionRequest request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        return CompletableFuture.completedFuture(
                PopulationAdmissionDecision.unavailable(UNAVAILABLE)
        );
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationAdmissionDecision> tryAdmitV2(
            @Nonnull PopulationAdmissionRequestV2 request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        return CompletableFuture.completedFuture(
                PopulationAdmissionDecision.unavailable(
                        "population-admission-v2-authority-unavailable"
                )
        );
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationAdmissionDecision> tryAdmitV3(
            @Nonnull PopulationAdmissionRequestV3 request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        if (!ready(request.managedProfileId())) {
            return CompletableFuture.completedFuture(
                    PopulationAdmissionDecision.unavailable(
                            "population-admission-v3-authority-unavailable"
                    )
            );
        }
        PopulationAdmissionStaging.Identity identity = staging.identity(request);
        OperationId operationId = new OperationId(identity.operationId());
        return author.author(
                        operationId,
                        identity.reservationId(),
                        request
                )
                .thenCompose(evidence -> staging.prepareOrReuse(identity, evidence))
                .exceptionally(this::failure);
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(
            @Nonnull PopulationBatchAdmissionRequest request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        return CompletableFuture.completedFuture(
                PopulationBatchAdmissionDecision.unavailable(
                        request.units().size(),
                        "population-admission-batch-authority-unavailable"
                )
        );
    }

    @Override
    @Nonnull
    public PopulationAdmissionDecision claimForApply(
            @Nonnull PopulationAdmissionToken token
    ) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        return staging.claimForApply(token);
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationAdmissionDecision> commit(
            @Nonnull PopulationAdmissionToken token
    ) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        return staging.settle(token, false);
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationAdmissionDecision> cancel(
            @Nonnull PopulationAdmissionToken token
    ) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        return staging.settle(token, true);
    }

    @Override
    @Nonnull
    public CompletionStage<Integer> cleanupExpired() {
        return staging.cleanupExpired();
    }

    @Override
    @Nonnull
    public RequiredContentProfileStatus status(@Nonnull String profileId) {
        if (profileId == null) {
            throw new NullPointerException("profileId");
        }
        ManagedActivityConfigRegistry.Readiness readiness = managed.readiness(profileId);
        AdmissionProviderRegistry.ProviderReadiness provider =
                readiness.providerId().isBlank()
                        ? null
                        : providers.readiness(
                                readiness.providerId(),
                                readiness.providerContractVersion()
                        );
        boolean providerAvailable = provider != null && provider.available();
        String detail = !readiness.available()
                ? readiness.detail()
                : providerAvailable
                        ? readiness.detail()
                        : provider == null
                                ? "provider-not-registered"
                                : provider.detail();
        return new RequiredContentProfileStatus(
                readiness.profileId().isBlank() ? profileId : readiness.profileId(),
                readiness.available() && providerAvailable,
                readiness.providerId(),
                readiness.providerContractVersion(),
                readiness.configRevision(),
                detail
        );
    }

    private boolean ready(String profileId) {
        if (persistence.readiness(PublicPersistenceFeatureRegistry.POPULATION_DOMAINS)
                != PersistenceReadinessLevel.MUTATION_READY) {
            return false;
        }
        return status(profileId).available();
    }

    private PopulationAdmissionDecision failure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException completion
                ? completion.getCause()
                : failure;
        if (cause instanceof ManagedAdmissionEvidenceAuthor.AdmissionDeniedException) {
            return new PopulationAdmissionDecision(
                    PopulationAdmissionDecision.Status.DENIED,
                    cause.getMessage(),
                    null,
                    OwnerPopulationCapDecisionViewV2.Readiness.READY,
                    0,
                    0
            );
        }
        return PopulationAdmissionDecision.unavailable(
                cause == null || cause.getMessage() == null
                        ? "population-admission-provider-unavailable"
                        : cause.getMessage()
        );
    }
}
