package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.api.RequiredContentProfileStatus;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.managed.ManagedActivityProfile;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigDefinition;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Freezes managed profile, provider, and weighted-domain evidence before persistence. */
public final class ManagedAdmissionEvidenceAuthor {
    private final ManagedActivityConfigRegistry managed;
    private final PopulationGroupConfigRegistry populationGroups;
    private final AdmissionProviderRegistry providers;
    private final LongSupplier clock;

    public ManagedAdmissionEvidenceAuthor(
            @Nonnull ManagedActivityConfigRegistry managed,
            @Nonnull PopulationGroupConfigRegistry populationGroups,
            @Nonnull AdmissionProviderRegistry providers,
            @Nonnull LongSupplier clock
    ) {
        this.managed = Objects.requireNonNull(managed, "managed");
        this.populationGroups = Objects.requireNonNull(populationGroups, "populationGroups");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Authorizes one public V3 request without opening the SQLite writer. */
    @Nonnull
    public CompletionStage<Authoring> author(
            @Nonnull OperationId operationId,
            @Nonnull UUID reservationId,
            @Nonnull PopulationAdmissionRequestV3 request
    ) {
        PopulationAdmissionRequest admission = request == null
                ? null : request.request().request();
        return author(
                operationId,
                reservationId,
                request,
                admission == null ? null : before(admission),
                admission == null ? null : map(admission.targetLifecycle())
        );
    }

    /**
     * Authorizes one request with the exact canonical source and target states.
     * Lifecycle adapters use this overload so stored-state transitions do not
     * infer the source from the requested target.
     */
    @Nonnull
    public CompletionStage<Authoring> author(
            @Nonnull OperationId operationId,
            @Nonnull UUID reservationId,
            @Nonnull PopulationAdmissionRequestV3 request,
            @Nullable LifecycleState beforeState,
            @Nonnull LifecycleState afterState
    ) {
        PopulationAdmissionRequest admission = request == null
                ? null : request.request().request();
        return author(
                operationId,
                reservationId,
                request,
                beforeState,
                afterState,
                admission == null || admission.oldOwnerUuid() == null
                        ? null : new OwnerId(admission.oldOwnerUuid()),
                admission == null || admission.source() == null
                        ? null : admission.source().worldName()
        );
    }

    /** Authorizes with exact canonical source ownership and world evidence. */
    @Nonnull
    public CompletionStage<Authoring> author(
            @Nonnull OperationId operationId,
            @Nonnull UUID reservationId,
            @Nonnull PopulationAdmissionRequestV3 request,
            @Nullable LifecycleState beforeState,
            @Nonnull LifecycleState afterState,
            @Nullable OwnerId sourceOwner,
            @Nullable String sourceWorld
    ) {
        if (operationId == null || reservationId == null || request == null) {
            throw new IllegalArgumentException("Complete managed admission request is required");
        }
        if (afterState == null) {
            throw new IllegalArgumentException("Target lifecycle state is required");
        }
        PopulationAdmissionRequest admission = request.request().request();
        ManagedActivityConfigRegistry.Readiness readiness = managed.readiness(
                request.managedProfileId()
        );
        if (!readiness.available()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("managed-profile-" + readiness.detail())
            );
        }
        ManagedActivityConfigRegistry.RoleResolution resolution = managed
                .resolveRole(request.request().targetRoleId())
                .orElse(null);
        if (resolution == null || !resolution.profile().profileId()
                .equals(request.managedProfileId())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("managed-role-unresolved")
            );
        }
        AdmissionProviderRegistry.ProviderReadiness providerReadiness =
                providers.readiness(readiness.providerId(), readiness.providerContractVersion());
        if (!ManagedAdmissionEvidenceSupport.positiveTransition(
                sourceOwner,
                sourceWorld,
                beforeState,
                request.request().request().newOwnerUuid() == null
                        ? null : new OwnerId(request.request().request().newOwnerUuid()),
                ManagedAdmissionEvidenceSupport.targetWorld(request),
                afterState,
                resolution.profile()
        )) {
            PopulationAdmissionProviderDecision decision =
                    new PopulationAdmissionProviderDecision(
                            PopulationAdmissionProviderStatus.ALLOW,
                            "provider-not-required",
                            Set.of(),
                            Map.of(),
                            0,
                            readiness.configRevision()
                    );
            try {
                return CompletableFuture.completedFuture(new Authoring(
                        payload(
                                operationId,
                                reservationId,
                                request,
                                readiness,
                                providerReadiness,
                                resolution,
                                decision,
                                sourceOwner,
                                sourceWorld,
                                beforeState,
                                afterState,
                                1,
                                List.of()
                        ),
                        readiness,
                        providerReadiness,
                        decision
                ));
            } catch (RuntimeException invalid) {
                return CompletableFuture.failedFuture(invalid);
            }
        }
        if (!providerReadiness.available()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("provider-" + providerReadiness.detail())
            );
        }
        PopulationAdmissionProviderRequest providerRequest = new PopulationAdmissionProviderRequest(
                readiness.providerId(),
                readiness.providerContractVersion(),
                request,
                resolution.family().groupId(),
                resolution.profile().families().keySet(),
                resolution.family().gateKey(),
                resolution.family().weight(),
                readiness.configRevision()
        );
        return providers.evaluate(providerRequest).thenCompose(decision -> {
            if (decision == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("provider-null-decision")
                );
            }
            if (decision.status() == PopulationAdmissionProviderStatus.DENY) {
                return CompletableFuture.failedFuture(
                        new AdmissionDeniedException(decision.messageKey())
                );
            }
            if (decision.status() != PopulationAdmissionProviderStatus.ALLOW) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "provider-unavailable:" + decision.messageKey()
                ));
            }
            if (decision.configRevision() != readiness.configRevision()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "provider-config-revision-stale"
                ));
            }
            ManagedActivityConfigRegistry.Readiness currentConfig =
                    managed.readiness(request.managedProfileId());
            AdmissionProviderRegistry.ProviderReadiness currentProvider =
                    providers.readiness(
                            readiness.providerId(),
                            readiness.providerContractVersion()
                    );
            if (!currentConfig.available()
                    || currentConfig.configRevision() != readiness.configRevision()
                    || !currentConfig.providerId().equals(readiness.providerId())
                    || currentConfig.providerContractVersion()
                    != readiness.providerContractVersion()
                    || !currentProvider.available()
                    || !currentProvider.generationToken().equals(
                            providerReadiness.generationToken()
                    )) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "provider-or-managed-config-stale"
                ));
            }
            try {
                PopulationDomainAdmissionOperation.Payload payload = payload(
                        operationId,
                        reservationId,
                        request,
                        readiness,
                        providerReadiness,
                        resolution,
                        decision,
                        sourceOwner,
                        sourceWorld,
                        beforeState,
                        afterState,
                        1,
                        List.of()
                );
                return CompletableFuture.completedFuture(new Authoring(
                        payload,
                        readiness,
                        providerReadiness,
                        decision
                ));
            } catch (RuntimeException invalid) {
                return CompletableFuture.failedFuture(invalid);
            }
        });
    }

    /** Authorizes one internal aggregate litter request with one provider call. */
    @Nonnull
    public CompletionStage<Authoring> authorBatch(
            @Nonnull ManagedBatchAdmissionRequest batch
    ) {
        PopulationAdmissionRequest admission = batch == null
                ? null : batch.admission().request().request();
        return authorBatch(
                batch,
                admission == null ? null : before(admission),
                admission == null || admission.oldOwnerUuid() == null
                        ? null : new OwnerId(admission.oldOwnerUuid()),
                admission == null || admission.source() == null
                        ? null : admission.source().worldName()
        );
    }

    /** Authorizes an aggregate with exact canonical source evidence. */
    @Nonnull
    public CompletionStage<Authoring> authorBatch(
            @Nonnull ManagedBatchAdmissionRequest batch,
            @Nullable LifecycleState beforeState,
            @Nullable OwnerId sourceOwner,
            @Nullable String sourceWorld
    ) {
        if (batch == null) {
            throw new IllegalArgumentException("Managed batch request is required");
        }
        return author(
                new OperationId(batch.litterOperationId()),
                UUID.nameUUIDFromBytes((batch.litterOperationId() + ":reservation")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                batch.admission(),
                beforeState,
                map(batch.admission().request().request().targetLifecycle()),
                sourceOwner,
                sourceWorld
        ).thenApply(authoring -> ManagedAdmissionBatchSupport.scale(
                authoring, batch.requestedUnits(), batch.provisionalChildIds()
        ));
    }

    @Nonnull
    public RequiredContentProfileStatus readiness(@Nonnull String profileId) {
        ManagedActivityConfigRegistry.Readiness value = managed.readiness(profileId);
        return new RequiredContentProfileStatus(
                value.profileId().isBlank() ? profileId : value.profileId(),
                value.available(),
                value.providerId(),
                value.providerContractVersion(),
                value.configRevision(),
                value.detail()
        );
    }

    private PopulationDomainAdmissionOperation.Payload payload(
            OperationId operationId,
            UUID reservationId,
            PopulationAdmissionRequestV3 request,
            ManagedActivityConfigRegistry.Readiness readiness,
            AdmissionProviderRegistry.ProviderReadiness providerReadiness,
            ManagedActivityConfigRegistry.RoleResolution resolution,
            PopulationAdmissionProviderDecision decision,
            @Nullable OwnerId sourceOwner,
            @Nullable String sourceWorld,
            @Nullable LifecycleState beforeState,
            @Nonnull LifecycleState afterState,
            int requestedCount,
            List<UUID> children
    ) {
        PopulationAdmissionRequest admission = request.request().request();
        OwnerId owner = admission.newOwnerUuid() == null
                ? null : new OwnerId(admission.newOwnerUuid());
        ProfileId profile = ManagedAdmissionEvidenceSupport.profileId(admission);
        LifecycleState target = afterState;
        LifecycleState before = beforeState;
        String world = owner == null
                ? null : ManagedAdmissionEvidenceSupport.targetWorld(request);
        ManagedActivityProfile profileConfig = resolution.profile();
        PopulationGroupConfigDefinition group = populationGroups.snapshot()
                .getDefinition(resolution.family().groupId())
                .orElseThrow(() -> new IllegalStateException("managed-group-missing"));
        PopulationDomainScope scope = PopulationDomainScope.valueOf(
                group.policy().scope().name()
        );
        Map<String, PopulationDomainClaim> claims = new LinkedHashMap<>();
        for (PopulationDomainClaim claim : decision.claims()) {
            if (claims.put(claim.domainId(), claim) != null) {
                throw new IllegalStateException("provider-duplicate-domain-claim");
            }
        }
        boolean providerRequired = !"provider-not-required".equals(
                decision.messageKey()
        );
        if (providerRequired && !claims.keySet().equals(profileConfig.domains().keySet())) {
            throw new IllegalStateException("provider-domain-claim-set-mismatch");
        }
        ArrayList<PopulationDomainAdmissionPlanner.DomainPolicy> policies = new ArrayList<>();
        for (ManagedActivityProfile.DomainDefinition definition : profileConfig.domains().values()) {
            PopulationDomainClaim claim = claims.get(definition.domainId());
            Integer limit = decision.domainLimits().get(definition.domainId());
            if (!providerRequired) {
                continue;
            }
            if (claim == null || limit == null
                    || claim.weight() <= 0
                    || claim.owned() != definition.owned()
                    || claim.deployable() != definition.deployable()
                    || limit < 0) {
                throw new IllegalStateException("provider-domain-evidence-mismatch");
            }
            policies.add(new PopulationDomainAdmissionPlanner.DomainPolicy(
                    definition.domainId(),
                    scope,
                    definition.owned(),
                    definition.deployable(),
                    claim.weight(),
                    definition.owned() ? limit : 0,
                    definition.deployable() ? limit : 0,
                    decision.snapshotRevision()
            ));
        }
        long now = clock.getAsLong();
        List<PopulationDomainAdmissionOperation.DomainInput> domains =
                PopulationDomainAdmissionPlanner.plan(
                        operationId,
                        profile,
                        admission.expectedProfileRevision() < 0
                                ? null
                                : new LifecycleRevision(admission.expectedProfileRevision()),
                        sourceOwner,
                        sourceWorld,
                        owner,
                        before,
                        target,
                        world,
                        policies,
                        decision.snapshotRevision(),
                        readiness.configRevision(),
                        now
                ).stream().map(reservation -> new PopulationDomainAdmissionOperation.DomainInput(
                        reservation.bucket().domainId(),
                        reservation.bucket().scope(),
                        reservation.bucket().ownerWorldKey(),
                        reservation.ownedDelta(),
                        reservation.deployableDelta(),
                        reservation.weight(),
                        reservation.snapshottedMaxOwned(),
                        reservation.snapshottedMaxDeployable(),
                        reservation.policyRevision()
                )).toList();
        return new PopulationDomainAdmissionOperation.Payload(
                reservationId,
                profile,
                owner,
                admission.expectedProfileRevision() < 0
                        ? null
                        : new LifecycleRevision(admission.expectedProfileRevision()),
                world,
                sourceOwner,
                sourceWorld,
                before,
                target,
                resolution.family().groupId(),
                providerReadiness.providerId(),
                providerReadiness.contractVersion(),
                providerReadiness.generationToken(),
                decision.snapshotRevision(),
                readiness.configRevision(),
                now + 30_000,
                requestedCount,
                domains,
                children,
                now
        );
    }

    private LifecycleState before(PopulationAdmissionRequest request) {
        if (request.operation() == com.alechilles.alecstamework.api.PopulationAdmissionOperation.NEW_OWNERSHIP
                || request.operation() == com.alechilles.alecstamework.api.PopulationAdmissionOperation.BREEDING
                || request.operation() == com.alechilles.alecstamework.api.PopulationAdmissionOperation.LEGACY_ADOPTION
                || request.operation() == com.alechilles.alecstamework.api.PopulationAdmissionOperation.RESTORE) {
            return null;
        }
        return map(request.targetLifecycle());
    }

    private LifecycleState map(PopulationCompanionLifecycle lifecycle) {
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

    /** Frozen evidence ready for one shared operation envelope. */
    public record Authoring(
            @Nonnull PopulationDomainAdmissionOperation.Payload payload,
            @Nonnull ManagedActivityConfigRegistry.Readiness readiness,
            @Nonnull AdmissionProviderRegistry.ProviderReadiness providerReadiness,
            @Nonnull PopulationAdmissionProviderDecision decision
    ) {
    }

    /** Stable denial marker used by the replacement facade. */
    public static final class AdmissionDeniedException extends RuntimeException {
        public AdmissionDeniedException(String messageKey) {
            super(messageKey == null || messageKey.isBlank()
                    ? "population-admission-denied"
                    : messageKey);
        }
    }
}
