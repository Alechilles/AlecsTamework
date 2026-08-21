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
import com.alechilles.alecstamework.api.internal.ManagedBatchAdmissionAuthority;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
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
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Replacement-persistence facade for staged provider-aware admission. */
public final class ReplacementPopulationAdmissionApi
        implements PopulationAdmissionApi, RequiredContentProfileApi,
        ManagedBatchAdmissionAuthority {
    private static final String UNAVAILABLE =
            "population-admission-authority-unavailable";

    private final PersistenceBootstrap persistence;
    private final Supplier<PopulationDomainAdmissionOperation> operations;
    private final ManagedAdmissionEvidenceAuthor author;
    private final ManagedActivityConfigRegistry managed;
    private final AdmissionProviderRegistry providers;
    private final PopulationGroupConfigRegistry populationGroups;
    private final PopulationAdmissionCompositionAuthor compositionAuthor;
    private final LongSupplier clock;
    private volatile PopulationAdmissionStaging staging;

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
                deferredOperations(publicOperations),
                new ManagedAdmissionEvidenceAuthor(
                        managed, populationGroups, providers, clock
                ),
                managed,
                providers,
                clock,
                populationGroups
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
        this(
                persistence,
                fixedOperations(operations),
                author,
                managed,
                providers,
                clock,
                null
        );
    }

    private ReplacementPopulationAdmissionApi(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull Supplier<PopulationDomainAdmissionOperation> operations,
            @Nonnull ManagedAdmissionEvidenceAuthor author,
            @Nonnull ManagedActivityConfigRegistry managed,
            @Nonnull AdmissionProviderRegistry providers,
            @Nonnull LongSupplier clock,
            @Nullable PopulationGroupConfigRegistry populationGroups
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.author = Objects.requireNonNull(author, "author");
        this.managed = Objects.requireNonNull(managed, "managed");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.populationGroups = populationGroups;
        this.compositionAuthor = new PopulationAdmissionCompositionAuthor(
                this.persistence, populationGroups
        );
        this.clock = Objects.requireNonNull(clock, "clock");
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
        if (!managedReady(request.managedProfileId())) {
            return CompletableFuture.completedFuture(
                    PopulationAdmissionDecision.unavailable(
                            "population-admission-v3-authority-unavailable"
                    )
            );
        }
        PopulationAdmissionStaging.Identity identity = staging().identity(request);
        OperationId operationId = new OperationId(identity.operationId());
        return canonicalSource(request)
                .thenCompose(source -> author.author(
                        operationId,
                        identity.reservationId(),
                        request,
                        source == null ? null : source.state(),
                        map(request.request().request().targetLifecycle()),
                        source == null ? null : source.ownerId(),
                        source == null ? null : source.ownerWorldKey()
                ).thenCompose(evidence -> compositionAuthor.compose(
                        request,
                        source,
                        evidence.payload(),
                        operationId
                ).thenCompose(composed -> staging().prepareOrReuse(
                        identity, evidence, composed
                ))))
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

    /** Internal aggregate litter path. The legacy public batch API remains unavailable. */
    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision> prepareManagedBatch(
            @Nonnull ManagedBatchAdmissionRequest request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        if (!managedReady(request.admission().managedProfileId())) {
            return CompletableFuture.completedFuture(
                    PopulationAdmissionDecision.unavailable(
                            "population-admission-batch-authority-unavailable"
                    )
            );
        }
        return canonicalSource(request.admission()).thenCompose(source ->
                staging().prepareBatch(request, author, source, compositionAuthor))
                .exceptionally(this::failure);
    }

    /** Internal claim boundary used immediately before the first litter child. */
    @Nonnull
    @Override
    public PopulationAdmissionDecision claimManagedBatch(
            @Nonnull PopulationAdmissionToken token
    ) {
        return claimForApply(token);
    }

    /** Internal durable recovery claim. It never blocks the world thread. */
    @Nonnull
    @Override
    public CompletionStage<PopulationAdmissionDecision>
    claimManagedBatchForRecovery(@Nonnull PopulationAdmissionToken token) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        return staging().claimForRecovery(token);
    }

    /** Internal exact ordinal settlement boundary for one managed litter. */
    @Nonnull
    @Override
    public CompletionStage<ManagedBatchSettlement> settleManagedBatch(
            @Nonnull PopulationAdmissionToken token,
            @Nonnull java.util.Set<Integer> settledOrdinals,
            @Nonnull java.util.Map<Integer, java.util.UUID> actualChildIds
    ) {
        if (token == null || settledOrdinals == null || actualChildIds == null) {
            throw new NullPointerException("batch settlement");
        }
        return staging().settleBatch(token, settledOrdinals, actualChildIds);
    }

    @Override
    @Nonnull
    public PopulationAdmissionDecision claimForApply(
            @Nonnull PopulationAdmissionToken token
    ) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        return staging().claimForApply(token);
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationAdmissionDecision> commit(
            @Nonnull PopulationAdmissionToken token
    ) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        return staging().settle(token, false);
    }

    @Override
    @Nonnull
    public CompletionStage<PopulationAdmissionDecision> cancel(
            @Nonnull PopulationAdmissionToken token
    ) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        return staging().settle(token, true);
    }

    @Override
    @Nonnull
    public CompletionStage<Integer> cleanupExpired() {
        return staging().cleanupExpired();
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

    private boolean managedReady(String profileId) {
        if (persistence.readiness(PublicPersistenceFeatureRegistry.POPULATION_DOMAINS)
                != PersistenceReadinessLevel.MUTATION_READY) {
            return false;
        }
        return managed.readiness(profileId).available();
    }

    private PopulationAdmissionStaging staging() {
        PopulationAdmissionStaging current = staging;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (staging == null) {
                staging = new PopulationAdmissionStaging(
                        Objects.requireNonNull(
                                operations.get(),
                                "Population admission operations are not ready"
                        ),
                        clock
                );
            }
            return staging;
        }
    }

    private static Supplier<PopulationDomainAdmissionOperation>
    deferredOperations(PublicPersistenceOperations operations) {
        PublicPersistenceOperations required = Objects.requireNonNull(
                operations, "publicOperations"
        );
        return required::populationDomainAdmission;
    }

    private static Supplier<PopulationDomainAdmissionOperation>
    fixedOperations(PopulationDomainAdmissionOperation operations) {
        PopulationDomainAdmissionOperation required = Objects.requireNonNull(
                operations, "operations"
        );
        return () -> required;
    }

    private CompletionStage<CompanionLifecycle> canonicalSource(
            PopulationAdmissionRequestV3 request
    ) {
        var admission = request.request().request();
        if (admission.expectedProfileRevision()
                == PopulationAdmissionRequest.NEW_PROFILE_REVISION) {
            return CompletableFuture.completedFuture(null);
        }
        String identity = admission.identity().canonicalProfileId();
        if (identity == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("population-admission-source-identity-missing")
            );
        }
        ProfileId profile;
        try {
            profile = ProfileId.parse(identity);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("population-admission-source-identity-invalid", invalid)
            );
        }
        return persistence.facades().queries().findAllLifecycles().thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<java.util.List<CompanionLifecycle>> found)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("population-admission-source-unavailable")
                );
            }
            CompanionLifecycle source = found.value().stream()
                    .filter(value -> value.profileId().equals(profile))
                    .findFirst().orElse(null);
            if (source == null || source.revision().value()
                    != admission.expectedProfileRevision()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("population-admission-source-stale")
                );
            }
            if (!java.util.Objects.equals(
                    source.ownerId(),
                    admission.oldOwnerUuid() == null
                            ? null
                            : new com.alechilles.alecstamework.companion.identity.OwnerId(
                                    admission.oldOwnerUuid()
                            )
            )) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("population-admission-source-owner-stale")
                );
            }
            return CompletableFuture.completedFuture(source);
        });
    }

    private LifecycleState map(
            com.alechilles.alecstamework.api.PopulationCompanionLifecycle lifecycle
    ) {
        return switch (lifecycle) {
            case ACTIVE -> LifecycleState.ACTIVE;
            case UNLOADED -> LifecycleState.UNLOADED;
            case CAPTURED -> LifecycleState.CAPTURED;
            case COOP -> LifecycleState.COOP;
            case DEAD_REVIVABLE -> LifecycleState.DEAD_REVIVABLE;
            case LOST -> LifecycleState.LOST;
            case ROSTER_STORED -> LifecycleState.ROSTER_STORED;
            case PROVISIONED_DORMANT -> LifecycleState.PROVISIONED_DORMANT;
            case RELEASED -> LifecycleState.RELEASED;
            case RESTORING, STORING -> LifecycleState.ACTIVE;
            case UNKNOWN_DORMANT -> LifecycleState.UNRESOLVED;
        };
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
