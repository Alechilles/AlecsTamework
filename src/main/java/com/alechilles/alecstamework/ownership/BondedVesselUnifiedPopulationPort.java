package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselMutationAuthority;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Production bonded-vessel adapter for the canonical owner, claim, and population-group
 * admission authority.
 *
 * <p>The world projection is deliberately not performed here. This adapter prepares and claims
 * one opaque population capability before the world port runs, then commits that exact
 * capability after the world receipt proves the planned projection was applied.</p>
 */
public final class BondedVesselUnifiedPopulationPort
        implements ProductionBondedVesselMutationAuthority.UnifiedPopulationPort {
    private static final String SOURCE = "bonded_vessel";

    private final OwnerPopulationRuntime runtime;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final ConcurrentHashMap<String, CompletionStage<
            ProductionBondedVesselMutationAuthority.PopulationPreparation>> preparations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PreparedContext> capabilities =
            new ConcurrentHashMap<>();

    public BondedVesselUnifiedPopulationPort(@Nonnull OwnerPopulationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.coordinator = runtime.companionAdmissionCoordinator();
        this.policyResolver = new CompanionAdmissionPolicyResolver(
                runtime.claimOccupancyIndex(), runtime.claimProviderRegistry());
    }

    @Nonnull
    @Override
    public CompletionStage<ProductionBondedVesselMutationAuthority.PopulationPreparation> prepare(
            @Nonnull ProductionBondedVesselMutationAuthority.PopulationMutationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!ready()) {
            return CompletableFuture.completedFuture(indeterminate(readiness().reason()));
        }
        return preparations.computeIfAbsent(request.operation().operationId(), ignored ->
                prepareOnce(request));
    }

    private CompletionStage<ProductionBondedVesselMutationAuthority.PopulationPreparation>
    prepareOnce(ProductionBondedVesselMutationAuthority.PopulationMutationRequest request) {
        final PreparedPlan plan;
        try {
            plan = buildPlan(request);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return CompletableFuture.completedFuture(denied(invalid.getMessage()));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(indeterminate(
                    "bonded-vessel-population-plan-unavailable"));
        }
        if (plan.plannedNpcUuid() != null && !runtime.identityResolver().retainPreparedAlias(
                request.profile().profileId(), plan.plannedNpcUuid())) {
            return CompletableFuture.completedFuture(denied(
                    "bonded-vessel-planned-identity-conflict"));
        }
        CompletionStage<CompanionPopulationPreparationResult> stage;
        try {
            stage = coordinator.prepareAsync(
                    plan.ownerPlan(), plan.claimRequest(), plan.lookupSession());
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(indeterminate(
                    "bonded-vessel-population-prepare-not-started"));
        }
        return stage.handle((result, failure) -> {
            if (failure != null || result == null) {
                releasePreparedIdentity(request, plan.plannedNpcUuid());
                return indeterminate("bonded-vessel-population-prepare-failed");
            }
            if (!result.allowed() || result.preparedAdmission() == null) {
                releasePreparedIdentity(request, plan.plannedNpcUuid());
                return denied(result.reason());
            }
            PreparedCompanionPopulationAdmission prepared = result.preparedAdmission();
            String capabilityId = prepared.ownerAdmission().operationId().toString();
            var handle = handle(request, capabilityId);
            PreparedContext context = new PreparedContext(request, handle, prepared,
                    plan.populationMayIncrease(), plan.plannedNpcUuid());
            PreparedContext raced = capabilities.putIfAbsent(capabilityId, context);
            if (raced != null && !raced.request().equals(request)) {
                coordinator.cancelAsync(prepared,
                        "bonded-vessel-population-capability-collision");
                releasePreparedIdentity(request, plan.plannedNpcUuid());
                return denied("bonded-vessel-population-capability-collision");
            }
            return new ProductionBondedVesselMutationAuthority.PopulationPreparation(
                    ProductionBondedVesselMutationAuthority.PopulationPreparationStatus.PREPARED,
                    result.reason(), raced == null ? handle : raced.handle());
        });
    }

    @Nonnull
    @Override
    public CompletionStage<ProductionBondedVesselMutationAuthority.PopulationClaim> claim(
            @Nonnull ProductionBondedVesselMutationAuthority.PopulationHandle handle) {
        Objects.requireNonNull(handle, "handle");
        PreparedContext context = capabilities.get(handle.capabilityId());
        if (context == null || !context.handle().equals(handle)) {
            return CompletableFuture.completedFuture(new
                    ProductionBondedVesselMutationAuthority.PopulationClaim(
                    ProductionBondedVesselMutationAuthority.PopulationClaimStatus.INDETERMINATE,
                    "bonded-vessel-population-capability-unavailable", null));
        }
        try {
            CompanionAdmissionPolicyResolver.Policy current = policyResolver.resolve(
                    OwnerPopulationOperation.LIFECYCLE_CHANGE,
                    context.populationMayIncrease());
            ClaimLookupSession refreshed = new ClaimLookupSession(
                    current.claimContext(), current.claimLimitPerChunk() > 0,
                    runtime.claimLookupMetrics());
            boolean claimed = coordinator.claimForApply(
                    context.prepared(), current.settingsRevision(), refreshed);
            return CompletableFuture.completedFuture(new
                    ProductionBondedVesselMutationAuthority.PopulationClaim(
                    claimed
                            ? ProductionBondedVesselMutationAuthority.PopulationClaimStatus.CLAIMED
                            : ProductionBondedVesselMutationAuthority.PopulationClaimStatus.TERMINAL_DENIED,
                    claimed ? "bonded-vessel-population-claimed"
                            : "bonded-vessel-population-claim-denied",
                    claimed ? handle : null));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(new
                    ProductionBondedVesselMutationAuthority.PopulationClaim(
                    ProductionBondedVesselMutationAuthority.PopulationClaimStatus.INDETERMINATE,
                    "bonded-vessel-population-claim-failed", null));
        }
    }

    @Nonnull
    @Override
    public CompletionStage<ProductionBondedVesselMutationAuthority.PopulationCommit> commit(
            @Nonnull ProductionBondedVesselMutationAuthority.PopulationHandle handle,
            @Nonnull ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
            @Nonnull ProductionBondedVesselMutationAuthority.WorldMutationReceipt worldReceipt) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(worldReceipt, "worldReceipt");
        PreparedContext context = capabilities.get(handle.capabilityId());
        if (context == null || !context.handle().equals(handle)
                || !context.request().equals(request)) {
            return CompletableFuture.completedFuture(commitResult(
                    ProductionBondedVesselMutationAuthority.PopulationCommitStatus.INDETERMINATE,
                    "bonded-vessel-population-capability-unavailable", request, worldReceipt));
        }
        String receiptDenial = validateReceipt(request, worldReceipt);
        if (receiptDenial != null) {
            coordinator.markCapabilityReadinessDegraded(receiptDenial);
            return CompletableFuture.completedFuture(commitResult(
                    ProductionBondedVesselMutationAuthority.PopulationCommitStatus.QUARANTINED,
                    receiptDenial, request, worldReceipt));
        }
        CompletionStage<CompanionPopulationCommitResult> stage;
        try {
            stage = coordinator.commitAsync(context.prepared());
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(commitResult(
                    ProductionBondedVesselMutationAuthority.PopulationCommitStatus.INDETERMINATE,
                    "bonded-vessel-population-commit-not-started", request, worldReceipt));
        }
        return stage.handle((result, failure) -> {
            if (failure != null || result == null) {
                return commitResult(
                        ProductionBondedVesselMutationAuthority.PopulationCommitStatus.INDETERMINATE,
                        "bonded-vessel-population-commit-failed", request, worldReceipt);
            }
            if (!result.committed()) {
                return commitResult(
                        ProductionBondedVesselMutationAuthority.PopulationCommitStatus.QUARANTINED,
                        result.reason(), request, worldReceipt);
            }
            if (!publishIdentity(context, worldReceipt)) {
                coordinator.markCapabilityReadinessDegraded(
                        "bonded-vessel-identity-publication-failed");
                return commitResult(
                        ProductionBondedVesselMutationAuthority.PopulationCommitStatus.QUARANTINED,
                        "bonded-vessel-identity-publication-failed", request, worldReceipt);
            }
            capabilities.remove(handle.capabilityId(), context);
            preparations.remove(request.operation().operationId());
            return commitResult(
                    ProductionBondedVesselMutationAuthority.PopulationCommitStatus.APPLIED,
                    result.reason(), request, worldReceipt);
        });
    }

    @Nonnull
    @Override
    public CompletionStage<Boolean> cancel(
            @Nonnull ProductionBondedVesselMutationAuthority.PopulationHandle handle,
            @Nonnull String reason) {
        Objects.requireNonNull(handle, "handle");
        PreparedContext context = capabilities.remove(handle.capabilityId());
        if (context == null || !context.handle().equals(handle)) {
            return CompletableFuture.completedFuture(false);
        }
        preparations.remove(context.request().operation().operationId());
        releasePreparedIdentity(context.request(), context.plannedNpcUuid());
        try {
            CompletionStage<Boolean> stage = coordinator.cancelAsync(
                    context.prepared(), requireText(reason, "reason"));
            return stage == null ? CompletableFuture.completedFuture(false) : stage;
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Installs the exact claimed UUID and owner into NPCPlugin's pre-add holder. */
    @Nonnull
    public OwnerComponentMutationService.WriteResult writeSpawnHolder(
            @Nonnull ProductionBondedVesselMutationAuthority.PopulationHandle handle,
            @Nonnull Holder<EntityStore> holder) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(holder, "holder");
        PreparedContext context = capabilities.get(handle.capabilityId());
        if (context == null || !context.handle().equals(handle)
                || context.plannedNpcUuid() == null) {
            return OwnerComponentMutationService.WriteResult.notApplied(
                    "bonded-vessel-population-capability-unavailable");
        }
        return runtime.mutationService().writeClaimedSpawnHolder(
                holder, context.prepared().ownerAdmission(), context.plannedNpcUuid(),
                context.request().profile().ownerUuid(), null);
    }

    @Nonnull
    @Override
    public ProductionBondedVesselMutationAuthority.PopulationReadiness readiness() {
        boolean owner = runtime.index().readiness() == OwnerPopulationReadiness.READY;
        boolean claim = runtime.claimOccupancyIndex().readiness() == ClaimOccupancyReadiness.READY;
        boolean groups = runtime.populationGroupsReady();
        boolean all = owner && claim && groups;
        String reason = all ? "bonded-vessel-population-ready"
                : !owner ? "bonded-vessel-owner-population-not-ready"
                : !claim ? "bonded-vessel-claim-population-not-ready"
                : "bonded-vessel-population-groups-not-ready";
        return new ProductionBondedVesselMutationAuthority.PopulationReadiness(
                owner, claim, groups, all, all, reason);
    }

    private boolean ready() {
        var readiness = readiness();
        return readiness.ownerAdmissionReady() && readiness.claimAdmissionReady()
                && readiness.groupAdmissionReady() && readiness.atomicCommitReady()
                && readiness.recoveryReady();
    }

    private PreparedPlan buildPlan(
            ProductionBondedVesselMutationAuthority.PopulationMutationRequest request) {
        var profile = request.profile();
        OwnerPopulationEntry current = runtime.index().entry(profile.profileId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "bonded-vessel-owner-population-profile-missing"));
        if (!Objects.equals(current.ownerId(), profile.ownerUuid())
                || current.revision() != profile.revision()
                || current.lifecycleState() != profile.lifecycle()) {
            throw new IllegalArgumentException(
                    "bonded-vessel-owner-population-profile-changed");
        }
        ClaimOccupancyEntry currentClaim = runtime.claimOccupancyIndex()
                .entry(profile.profileId()).orElseThrow(() -> new IllegalArgumentException(
                        "bonded-vessel-claim-population-profile-missing"));
        if (currentClaim.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("bonded-vessel-claim-revision-exhausted");
        }
        PopulationAdmissionLocation destination = destination(request.operation());
        boolean targetActive = request.targetLifecycle() == CompanionLifecycleState.ACTIVE;
        if (targetActive && destination == null) {
            throw new IllegalArgumentException("bonded-vessel-summon-destination-missing");
        }
        if (!targetActive && request.operation().action() == BondedVesselOperationRecord.Action.SUMMON) {
            throw new IllegalArgumentException("bonded-vessel-summon-target-not-active");
        }
        ClaimChunkCoordinate targetPhysical = targetActive
                ? new ClaimChunkCoordinate(destination.worldName(),
                        destination.chunkX(), destination.chunkZ())
                : currentClaim.physicalChunk();
        ClaimOccupancyEntry proposedClaim = new ClaimOccupancyEntry(
                profile.profileId(), profile.ownerUuid(), request.targetLifecycle(),
                targetPhysical, currentClaim.revision() + 1L);
        ClaimOccupancyTransition claimTransition = new ClaimOccupancyTransition(
                currentClaim, proposedClaim);
        boolean populationMayIncrease = !claimTransition.isKnownNonPositiveAtSameLocation();
        CompanionAdmissionPolicyResolver.Policy policy = policyResolver.resolve(
                OwnerPopulationOperation.LIFECYCLE_CHANGE, populationMayIncrease);
        String ownershipWorld = current.ownershipWorldName() == null && destination != null
                ? destination.worldName() : current.ownershipWorldName();
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profile.profileId(), current.revision(), current.ownerId(),
                current.ownershipWorldName(), profile.ownerUuid(), ownershipWorld,
                request.targetLifecycle(), OwnerPopulationOperation.LIFECYCLE_CHANGE,
                policy.scope(), policy.limit(), false);
        long now = System.currentTimeMillis();
        ClaimChunkCoordinate baselinePhysical = currentClaim.physicalChunk();
        CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                profile.profileId(), profile.currentNpcUuid(), profile.ownerUuid(),
                baselinePhysical == null ? ownershipWorld : baselinePhysical.worldName(),
                current.ownershipWorldName(), profile.lifecycle().name(),
                baselinePhysical == null ? null : baselinePhysical.worldName(),
                baselinePhysical == null ? null : baselinePhysical.chunkX(),
                baselinePhysical == null ? null : baselinePhysical.chunkZ(),
                profile.revision(), SOURCE, now, now);
        UUID plannedNpcUuid = targetActive ? plannedNpcUuid(request.operation()) : null;
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                transition, baseline, plannedNpcUuid,
                targetActive ? destination.worldName() : null,
                targetActive ? destination.chunkX() : null,
                targetActive ? destination.chunkZ() : null,
                SOURCE, ownerJson(profile.ownerUuid()), ownerJson(profile.ownerUuid()),
                contextJson(request, plannedNpcUuid), policy.settingsRevision(),
                policy.claimContext().providerGeneration(),
                PopulationGroupRoleContext.unchanged(profile.roleId()));
        ClaimAdmissionRequest claimRequest = new ClaimAdmissionRequest(
                ClaimAdmissionOperation.EXTERNAL, List.of(claimTransition),
                proposedClaim.occupiesClaim() ? targetPhysical : null,
                policy.claimContext(), policy.claimLimitPerChunk(),
                policy.claimLimitTotal(), policy.requireClaim(), false,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos());
        ClaimLookupSession lookup = new ClaimLookupSession(
                policy.claimContext(), policy.claimLimitPerChunk() > 0,
                runtime.claimLookupMetrics());
        return new PreparedPlan(ownerPlan, claimRequest, lookup, populationMayIncrease,
                plannedNpcUuid);
    }

    @Nullable
    private static PopulationAdmissionLocation destination(BondedVesselOperationRecord operation) {
        if (operation.sourceContextJson() == null) return null;
        try {
            JsonObject json = JsonParser.parseString(operation.sourceContextJson()).getAsJsonObject();
            if (!json.has("destinationWorld") || json.get("destinationWorld").isJsonNull()) {
                return null;
            }
            if (!json.has("destinationChunkX") || !json.has("destinationChunkZ")) {
                throw new IllegalArgumentException("bonded-vessel-destination-incomplete");
            }
            return new PopulationAdmissionLocation(json.get("destinationWorld").getAsString(),
                    json.get("destinationChunkX").getAsInt(),
                    json.get("destinationChunkZ").getAsInt());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("bonded-vessel-source-context-invalid", invalid);
        }
    }

    /** Stable live projection identity shared by population admission and the world port. */
    @Nonnull
    public static UUID plannedNpcUuid(@Nonnull BondedVesselOperationRecord operation) {
        Objects.requireNonNull(operation, "operation");
        String material = "bonded-vessel:" + operation.operationId() + ":"
                + operation.candidateGeneration();
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static String validateReceipt(
            ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
            ProductionBondedVesselMutationAuthority.WorldMutationReceipt receipt) {
        if (request.targetLifecycle() == CompanionLifecycleState.ACTIVE) {
            UUID planned = plannedNpcUuid(request.operation());
            PopulationAdmissionLocation destination = destination(request.operation());
            BondedVesselBindingRecord.PhysicalLocation expected =
                    new BondedVesselBindingRecord.PhysicalLocation(destination.worldName(),
                            destination.chunkX(), destination.chunkZ());
            if (!planned.equals(receipt.activeNpcUuid())) {
                return "bonded-vessel-world-npc-identity-mismatch";
            }
            if (!expected.equals(receipt.activeLocation())) {
                return "bonded-vessel-world-location-mismatch";
            }
        } else if (receipt.activeNpcUuid() != null || receipt.activeLocation() != null) {
            return "bonded-vessel-world-removal-receipt-mismatch";
        }
        return null;
    }

    private static ProductionBondedVesselMutationAuthority.PopulationHandle handle(
            ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
            String capabilityId) {
        return new ProductionBondedVesselMutationAuthority.PopulationHandle(
                request.operation().operationId(), request.binding().bindingId(),
                request.profile().profileId(), request.operation().priorGeneration(),
                request.operation().candidateGeneration(), capabilityId);
    }

    private static ProductionBondedVesselMutationAuthority.PopulationPreparation denied(
            @Nullable String reason) {
        return new ProductionBondedVesselMutationAuthority.PopulationPreparation(
                ProductionBondedVesselMutationAuthority.PopulationPreparationStatus.TERMINAL_DENIED,
                normalize(reason, "bonded-vessel-population-denied"), null);
    }

    private static ProductionBondedVesselMutationAuthority.PopulationPreparation indeterminate(
            @Nullable String reason) {
        return new ProductionBondedVesselMutationAuthority.PopulationPreparation(
                ProductionBondedVesselMutationAuthority.PopulationPreparationStatus.INDETERMINATE,
                normalize(reason, "bonded-vessel-population-indeterminate"), null);
    }

    private static ProductionBondedVesselMutationAuthority.PopulationCommit commitResult(
            ProductionBondedVesselMutationAuthority.PopulationCommitStatus status,
            String reason,
            ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
            ProductionBondedVesselMutationAuthority.WorldMutationReceipt receipt) {
        long revision = request.profile().revision() == Long.MAX_VALUE
                ? Long.MAX_VALUE : request.profile().revision() + 1L;
        return new ProductionBondedVesselMutationAuthority.PopulationCommit(
                status, normalize(reason, "bonded-vessel-population-commit-indeterminate"),
                revision, receipt.activeNpcUuid(), receipt.activeLocation(),
                receipt.itemEvidenceJson());
    }

    private static String ownerJson(UUID ownerUuid) {
        JsonObject json = new JsonObject();
        json.addProperty("ownerUuid", ownerUuid.toString());
        return json.toString();
    }

    private static String contextJson(
            ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
            @Nullable UUID plannedNpcUuid) {
        JsonObject json = new JsonObject();
        json.addProperty("vesselOperationId", request.operation().operationId());
        json.addProperty("bindingId", request.binding().bindingId());
        json.addProperty("priorGeneration", request.operation().priorGeneration());
        json.addProperty("candidateGeneration", request.operation().candidateGeneration());
        json.addProperty("action", request.operation().action().name());
        if (plannedNpcUuid != null) json.addProperty("plannedNpcUuid", plannedNpcUuid.toString());
        return json.toString();
    }

    private static String normalize(@Nullable String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private boolean publishIdentity(
            PreparedContext context,
            ProductionBondedVesselMutationAuthority.WorldMutationReceipt receipt) {
        try {
            if (context.plannedNpcUuid() != null) {
                runtime.identityResolver().remap(context.request().profile().profileId(),
                        context.request().profile().currentNpcUuid(), context.plannedNpcUuid());
                runtime.identityResolver().markDurable(
                        context.request().profile().profileId(), context.plannedNpcUuid());
                return context.plannedNpcUuid().equals(receipt.activeNpcUuid());
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private void releasePreparedIdentity(
            ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
            @Nullable UUID plannedNpcUuid) {
        if (plannedNpcUuid == null) return;
        try {
            runtime.identityResolver().releasePreparedAlias(
                    request.profile().profileId(), plannedNpcUuid);
        } catch (RuntimeException | LinkageError ignored) {
            coordinator.markCapabilityReadinessDegraded(
                    "bonded-vessel-planned-identity-release-failed");
        }
    }

    private record PreparedPlan(OwnerPopulationAdmissionPlan ownerPlan,
                                ClaimAdmissionRequest claimRequest,
                                ClaimLookupSession lookupSession,
                                boolean populationMayIncrease,
                                @Nullable UUID plannedNpcUuid) {
    }

    private record PreparedContext(
            ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
            ProductionBondedVesselMutationAuthority.PopulationHandle handle,
            PreparedCompanionPopulationAdmission prepared,
            boolean populationMayIncrease,
            @Nullable UUID plannedNpcUuid) {
    }
}
