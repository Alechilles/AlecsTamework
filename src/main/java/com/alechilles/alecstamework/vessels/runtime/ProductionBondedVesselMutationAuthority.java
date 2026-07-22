package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.vessels.BondedVesselMutationAuthority;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Non-blocking production orchestration for canonical vessel lifecycle mutations.
 *
 * <p>Composition must provide the existing canonical profile authority, the unified
 * owner/claim/group admission authority, and a lease-bound world dispatcher through the ports
 * below. This class intentionally has no repository fallback: a partially composed runtime is
 * not capability-ready and fails closed.</p>
 */
public final class ProductionBondedVesselMutationAuthority
        implements BondedVesselMutationAuthority {
    private static final String EMPTY_EVIDENCE_JSON = "{}";

    private final CanonicalProfilePort profiles;
    private final UnifiedPopulationPort populations;
    private final WorldProjectionPort world;
    private final Executor coordinationExecutor;

    public ProductionBondedVesselMutationAuthority(
            @Nonnull CanonicalProfilePort profiles,
            @Nonnull UnifiedPopulationPort populations,
            @Nonnull WorldProjectionPort world,
            @Nonnull Executor coordinationExecutor
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.populations = Objects.requireNonNull(populations, "populations");
        this.world = Objects.requireNonNull(world, "world");
        this.coordinationExecutor = Objects.requireNonNull(
                coordinationExecutor, "coordinationExecutor");
    }

    @Nonnull
    public Readiness readiness() {
        ProfileReadiness profile = safeProfileReadiness();
        PopulationReadiness population = safePopulationReadiness();
        WorldReadiness projection = safeWorldReadiness();
        boolean ready = profile.canonicalReadReady()
                && population.ownerAdmissionReady()
                && population.claimAdmissionReady()
                && population.groupAdmissionReady()
                && population.atomicCommitReady()
                && population.recoveryReady()
                && projection.leaseBoundDispatchReady()
                && projection.roleProjectionReady()
                && projection.recoveryReady();
        String reason = ready ? "bonded-vessel-mutation-ready"
                : firstUnavailableReason(profile, population, projection);
        return new Readiness(
                profile.canonicalReadReady(),
                population.ownerAdmissionReady(),
                population.claimAdmissionReady(),
                population.groupAdmissionReady(),
                population.atomicCommitReady(),
                population.recoveryReady(),
                projection.leaseBoundDispatchReady(),
                projection.roleProjectionReady(),
                projection.recoveryReady(),
                ready,
                reason);
    }

    public boolean isCapabilityReady() {
        return readiness().capabilityReady();
    }

    @Override
    @Nonnull
    public CompletionStage<ApplyOutcome> apply(
            @Nonnull BondedVesselOperationRecord operation,
            @Nonnull BondedVesselBindingRecord binding,
            boolean recovery
    ) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(binding, "binding");
        if (!isCapabilityReady()) {
            return completed(Status.INDETERMINATE,
                    readiness().reason(), operation.expectedProfileRevision(),
                    binding.activeNpcUuid(), binding.activeLocation(), binding.itemEvidenceJson());
        }
        String bindingDenial = validateApplyingBinding(operation, binding);
        if (bindingDenial != null) {
            Status status = bindingDenial.startsWith("quarantine-")
                    ? Status.QUARANTINED : Status.TERMINAL_DENIED;
            return completed(status, bindingDenial, operation.expectedProfileRevision(),
                    binding.activeNpcUuid(), binding.activeLocation(), binding.itemEvidenceJson());
        }

        return invokeOffThread(() -> profiles.load(operation.profileId()))
                .thenComposeAsync(profile -> continueWithProfile(
                        operation, binding, profile, recovery), coordinationExecutor)
                .exceptionally(failure -> outcome(Status.INDETERMINATE,
                        "bonded-vessel-mutation-stage-failed",
                        operation.expectedProfileRevision(), binding.activeNpcUuid(),
                        binding.activeLocation(), binding.itemEvidenceJson()));
    }

    private CompletionStage<ApplyOutcome> continueWithProfile(
            BondedVesselOperationRecord operation,
            BondedVesselBindingRecord binding,
            @Nullable CanonicalProfileSnapshot profile,
            boolean recovery
    ) {
        String denial = validateProfile(operation, binding, profile);
        if (denial != null) {
            return completed(Status.TERMINAL_DENIED, denial,
                    operation.expectedProfileRevision(), binding.activeNpcUuid(),
                    binding.activeLocation(), binding.itemEvidenceJson());
        }
        PopulationMutationRequest request = new PopulationMutationRequest(
                operation, binding, Objects.requireNonNull(profile, "profile"),
                targetLifecycle(operation.action()), recovery);
        return invokeOffThread(() -> populations.prepare(request))
                .thenComposeAsync(preparation -> handlePreparation(
                        request, preparation), coordinationExecutor);
    }

    private CompletionStage<ApplyOutcome> handlePreparation(
            PopulationMutationRequest request,
            @Nullable PopulationPreparation preparation
    ) {
        if (preparation == null) {
            return completed(Status.INDETERMINATE,
                    "population-preparation-missing", request.operation().expectedProfileRevision(),
                    request.binding().activeNpcUuid(), request.binding().activeLocation(),
                    request.binding().itemEvidenceJson());
        }
        if (preparation.status() == PopulationPreparationStatus.TERMINAL_DENIED) {
            return completed(Status.TERMINAL_DENIED, preparation.reason(),
                    request.operation().expectedProfileRevision(),
                    request.binding().activeNpcUuid(), request.binding().activeLocation(),
                    request.binding().itemEvidenceJson());
        }
        if (preparation.status() != PopulationPreparationStatus.PREPARED
                || preparation.handle() == null) {
            return completed(Status.INDETERMINATE, preparation.reason(),
                    request.operation().expectedProfileRevision(),
                    request.binding().activeNpcUuid(), request.binding().activeLocation(),
                    request.binding().itemEvidenceJson());
        }
        PopulationHandle handle = preparation.handle();
        if (!handle.matches(request.operation(), request.binding())) {
            return cancelThen(request, handle, "population-capability-mismatch",
                    Status.QUARANTINED);
        }
        return invokeOffThread(() -> populations.claim(handle))
                .thenComposeAsync(claim -> handleClaim(request, handle, claim), coordinationExecutor);
    }

    private CompletionStage<ApplyOutcome> handleClaim(
            PopulationMutationRequest request,
            PopulationHandle handle,
            @Nullable PopulationClaim claim
    ) {
        if (claim == null || claim.status() == PopulationClaimStatus.INDETERMINATE) {
            return completed(Status.INDETERMINATE,
                    claim == null ? "population-claim-missing" : claim.reason(),
                    request.operation().expectedProfileRevision(),
                    request.binding().activeNpcUuid(), request.binding().activeLocation(),
                    request.binding().itemEvidenceJson());
        }
        if (claim.status() == PopulationClaimStatus.TERMINAL_DENIED) {
            return cancelThen(request, handle, claim.reason(), Status.TERMINAL_DENIED);
        }
        if (!claim.handle().matches(request.operation(), request.binding())) {
            return cancelThen(request, handle, "claimed-population-capability-mismatch",
                    Status.QUARANTINED);
        }

        CompletionStage<WorldMutationReceipt> worldStage;
        try {
            worldStage = world.apply(new WorldMutationRequest(request, claim.handle()));
        } catch (RuntimeException | LinkageError failure) {
            worldStage = null;
        }
        if (worldStage == null) {
            return cancelThen(request, handle, "world-dispatch-not-started", Status.INDETERMINATE);
        }
        return worldStage.handle((receipt, failure) -> failure == null ? receipt : null)
                .thenComposeAsync(receipt -> handleWorldReceipt(
                        request, handle, receipt), coordinationExecutor);
    }

    private CompletionStage<ApplyOutcome> handleWorldReceipt(
            PopulationMutationRequest request,
            PopulationHandle handle,
            @Nullable WorldMutationReceipt receipt
    ) {
        if (receipt == null) {
            return completed(Status.INDETERMINATE, "world-mutation-indeterminate",
                    request.operation().expectedProfileRevision(),
                    request.binding().activeNpcUuid(), request.binding().activeLocation(),
                    request.binding().itemEvidenceJson());
        }
        return switch (receipt.status()) {
            case TERMINAL_DENIED -> cancelThen(
                    request, handle, receipt.reason(), Status.TERMINAL_DENIED);
            case QUARANTINED -> completed(Status.QUARANTINED, receipt.reason(),
                    request.operation().expectedProfileRevision(), receipt.activeNpcUuid(),
                    receipt.activeLocation(), receipt.itemEvidenceJson());
            case INDETERMINATE -> completed(Status.INDETERMINATE, receipt.reason(),
                    request.operation().expectedProfileRevision(), receipt.activeNpcUuid(),
                    receipt.activeLocation(), receipt.itemEvidenceJson());
            case APPLIED, ALREADY_APPLIED -> invokeOffThread(() -> populations.commit(
                            handle, request, receipt))
                    .thenApply(commit -> toOutcome(request, receipt, commit));
        };
    }

    private CompletionStage<ApplyOutcome> cancelThen(
            PopulationMutationRequest request,
            PopulationHandle handle,
            String reason,
            Status requestedStatus
    ) {
        return invokeOffThread(() -> populations.cancel(handle, reason))
                .handle((canceled, failure) -> failure == null && Boolean.TRUE.equals(canceled)
                        ? outcome(requestedStatus, reason,
                                request.operation().expectedProfileRevision(),
                                request.binding().activeNpcUuid(), request.binding().activeLocation(),
                                request.binding().itemEvidenceJson())
                        : outcome(Status.INDETERMINATE,
                                "population-cancellation-indeterminate",
                                request.operation().expectedProfileRevision(),
                                request.binding().activeNpcUuid(), request.binding().activeLocation(),
                                request.binding().itemEvidenceJson()));
    }

    private ApplyOutcome toOutcome(
            PopulationMutationRequest request,
            WorldMutationReceipt receipt,
            @Nullable PopulationCommit commit
    ) {
        if (commit == null) {
            return outcome(Status.INDETERMINATE, "canonical-population-commit-missing",
                    request.operation().expectedProfileRevision(), receipt.activeNpcUuid(),
                    receipt.activeLocation(), receipt.itemEvidenceJson());
        }
        Status status = switch (commit.status()) {
            case APPLIED -> Status.APPLIED;
            case ALREADY_APPLIED -> Status.ALREADY_APPLIED;
            // A world projection may already exist/be removed. A post-world commit denial cannot
            // prove that authoritative apply did not happen and therefore cannot be terminal.
            case TERMINAL_DENIED -> Status.QUARANTINED;
            case INDETERMINATE -> Status.INDETERMINATE;
            case QUARANTINED -> Status.QUARANTINED;
        };
        return outcome(status, commit.reason(), commit.committedProfileRevision(),
                commit.activeNpcUuid(), commit.activeLocation(), commit.itemEvidenceJson());
    }

    private <T> CompletionStage<T> invokeOffThread(
            Supplier<CompletionStage<T>> invocation
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return invocation.get();
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }, coordinationExecutor).thenCompose(stage -> stage == null
                ? CompletableFuture.completedFuture(null)
                : stage);
    }

    @Nullable
    private String validateApplyingBinding(
            BondedVesselOperationRecord operation,
            BondedVesselBindingRecord binding
    ) {
        if (!operation.bindingId().equals(binding.bindingId())) return "binding-id-mismatch";
        if (!operation.profileId().equals(binding.profileId())) return "profile-id-mismatch";
        if (operation.priorGeneration() != binding.generation()) return "stale-binding-generation";
        if (operation.expectedProfileRevision() != binding.expectedProfileRevision()) {
            return "canonical-profile-revision-changed";
        }
        if (!operation.operationId().equals(binding.activeOperationId())) {
            return "quarantine-active-operation-mismatch";
        }
        if (operation.applyingLifecycleState() != binding.lifecycleState()) {
            return "quarantine-applying-lifecycle-mismatch";
        }
        return null;
    }

    @Nullable
    private String validateProfile(
            BondedVesselOperationRecord operation,
            BondedVesselBindingRecord binding,
            @Nullable CanonicalProfileSnapshot profile
    ) {
        if (profile == null) return "canonical-profile-not-found";
        if (!profile.profileId().equals(operation.profileId())) return "canonical-profile-id-mismatch";
        if (!profile.ownerUuid().equals(binding.ownerUuid())) return "canonical-profile-owner-changed";
        CompanionLifecycleState expected = sourceProfileLifecycle(operation.priorLifecycleState());
        if (profile.lifecycle() != expected) return "canonical-profile-lifecycle-changed";
        if (profile.revision() != operation.expectedProfileRevision()
                && !allowsStoredProfileRevisionAdvance(operation, profile)) {
            return "canonical-profile-revision-changed";
        }
        if ((operation.action() == BondedVesselOperationRecord.Action.STORE
                || operation.action() == BondedVesselOperationRecord.Action.MARK_DEAD
                || operation.action() == BondedVesselOperationRecord.Action.MARK_LOST)
                && !Objects.equals(profile.currentNpcUuid(), binding.activeNpcUuid())) {
            return "canonical-live-projection-changed";
        }
        return null;
    }

    /**
     * Capture finalization removes the physical NPC after generation-one binding is committed.
     * That removal may advance the canonical CAPTURED revision without changing vessel state.
     */
    private static boolean allowsStoredProfileRevisionAdvance(
            BondedVesselOperationRecord operation,
            CanonicalProfileSnapshot profile) {
        return operation.action() == BondedVesselOperationRecord.Action.SUMMON
                && operation.priorLifecycleState()
                == BondedVesselBindingRecord.LifecycleState.STORED
                && profile.lifecycle() == CompanionLifecycleState.CAPTURED
                && profile.revision() > operation.expectedProfileRevision();
    }

    private ProfileReadiness safeProfileReadiness() {
        try {
            ProfileReadiness readiness = profiles.readiness();
            return readiness == null
                    ? new ProfileReadiness(false, "canonical-profile-readiness-missing") : readiness;
        } catch (RuntimeException | LinkageError failure) {
            return new ProfileReadiness(false, "canonical-profile-readiness-failed");
        }
    }

    private PopulationReadiness safePopulationReadiness() {
        try {
            PopulationReadiness readiness = populations.readiness();
            return readiness == null ? PopulationReadiness.unavailable(
                    "unified-population-readiness-missing") : readiness;
        } catch (RuntimeException | LinkageError failure) {
            return PopulationReadiness.unavailable("unified-population-readiness-failed");
        }
    }

    private WorldReadiness safeWorldReadiness() {
        try {
            WorldReadiness readiness = world.readiness();
            return readiness == null ? WorldReadiness.unavailable(
                    "world-projection-readiness-missing") : readiness;
        } catch (RuntimeException | LinkageError failure) {
            return WorldReadiness.unavailable("world-projection-readiness-failed");
        }
    }

    private static String firstUnavailableReason(
            ProfileReadiness profile,
            PopulationReadiness population,
            WorldReadiness world
    ) {
        if (!profile.canonicalReadReady()) return profile.reason();
        if (!(population.ownerAdmissionReady() && population.claimAdmissionReady()
                && population.groupAdmissionReady() && population.atomicCommitReady()
                && population.recoveryReady())) return population.reason();
        return world.reason();
    }

    private static CompanionLifecycleState sourceProfileLifecycle(
            BondedVesselBindingRecord.LifecycleState state
    ) {
        return switch (state) {
            case STORED -> CompanionLifecycleState.CAPTURED;
            case SUMMONING -> CompanionLifecycleState.CAPTURED;
            case ACTIVE, STORING -> CompanionLifecycleState.ACTIVE;
            case DEAD -> CompanionLifecycleState.DEAD_REVIVABLE;
            case LOST -> CompanionLifecycleState.LOST;
            case RELEASING, RELEASED -> CompanionLifecycleState.RELEASED;
        };
    }

    private static CompanionLifecycleState targetLifecycle(
            BondedVesselOperationRecord.Action action
    ) {
        return switch (action) {
            case INITIAL_BIND, STORE, REPAIR, REISSUE -> CompanionLifecycleState.CAPTURED;
            case SUMMON -> CompanionLifecycleState.ACTIVE;
            case MARK_DEAD -> CompanionLifecycleState.DEAD_REVIVABLE;
            case MARK_LOST -> CompanionLifecycleState.LOST;
            case RELEASE -> CompanionLifecycleState.RELEASED;
        };
    }

    private static CompletionStage<ApplyOutcome> completed(
            Status status,
            String reason,
            long revision,
            @Nullable UUID npcUuid,
            @Nullable BondedVesselBindingRecord.PhysicalLocation location,
            @Nullable String evidenceJson
    ) {
        return CompletableFuture.completedFuture(outcome(
                status, reason, revision, npcUuid, location, evidenceJson));
    }

    private static ApplyOutcome outcome(
            Status status,
            String reason,
            long revision,
            @Nullable UUID npcUuid,
            @Nullable BondedVesselBindingRecord.PhysicalLocation location,
            @Nullable String evidenceJson
    ) {
        return new ApplyOutcome(status, normalizeReason(reason), Math.max(0L, revision),
                npcUuid, location, normalizeEvidence(evidenceJson));
    }

    private static String normalizeReason(@Nullable String reason) {
        return reason == null || reason.isBlank()
                ? "bonded-vessel-mutation-indeterminate" : reason.trim();
    }

    private static String normalizeEvidence(@Nullable String evidence) {
        return evidence == null || evidence.isBlank() ? EMPTY_EVIDENCE_JSON : evidence.trim();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    /** Canonical profile reads are asynchronous and must be backed by NpcProfileRepository. */
    public interface CanonicalProfilePort {
        @Nonnull
        CompletionStage<CanonicalProfileSnapshot> load(@Nonnull String profileId);

        @Nonnull
        ProfileReadiness readiness();
    }

    /**
     * One unified owner/claim/group authority. Its commit owns the canonical profile/population
     * transaction and may not delegate to independent repository writes.
     */
    public interface UnifiedPopulationPort {
        @Nonnull
        CompletionStage<PopulationPreparation> prepare(@Nonnull PopulationMutationRequest request);

        @Nonnull
        CompletionStage<PopulationClaim> claim(@Nonnull PopulationHandle handle);

        @Nonnull
        CompletionStage<PopulationCommit> commit(
                @Nonnull PopulationHandle handle,
                @Nonnull PopulationMutationRequest request,
                @Nonnull WorldMutationReceipt worldReceipt
        );

        @Nonnull
        CompletionStage<Boolean> cancel(@Nonnull PopulationHandle handle, @Nonnull String reason);

        @Nonnull
        PopulationReadiness readiness();
    }

    /** World-owned apply; implementations dispatch through LeaseBoundWorldDispatcher. */
    public interface WorldProjectionPort {
        @Nonnull
        CompletionStage<WorldMutationReceipt> apply(@Nonnull WorldMutationRequest request);

        @Nonnull
        WorldReadiness readiness();
    }

    public record CanonicalProfileSnapshot(
            @Nonnull String profileId,
            @Nonnull UUID ownerUuid,
            @Nonnull String roleId,
            long revision,
            @Nonnull CompanionLifecycleState lifecycle,
            @Nullable UUID currentNpcUuid
    ) {
        public CanonicalProfileSnapshot {
            profileId = requireText(profileId, "profileId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            roleId = requireText(roleId, "roleId");
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
        }
    }

    public record PopulationMutationRequest(
            @Nonnull BondedVesselOperationRecord operation,
            @Nonnull BondedVesselBindingRecord binding,
            @Nonnull CanonicalProfileSnapshot profile,
            @Nonnull CompanionLifecycleState targetLifecycle,
            boolean recovery
    ) {
        public PopulationMutationRequest {
            operation = Objects.requireNonNull(operation, "operation");
            binding = Objects.requireNonNull(binding, "binding");
            profile = Objects.requireNonNull(profile, "profile");
            targetLifecycle = Objects.requireNonNull(targetLifecycle, "targetLifecycle");
        }
    }

    public record PopulationHandle(
            @Nonnull String operationId,
            @Nonnull String bindingId,
            @Nonnull String profileId,
            long priorGeneration,
            long candidateGeneration,
            @Nonnull String capabilityId
    ) {
        public PopulationHandle {
            operationId = requireText(operationId, "operationId");
            bindingId = requireText(bindingId, "bindingId");
            profileId = requireText(profileId, "profileId");
            capabilityId = requireText(capabilityId, "capabilityId");
            if (priorGeneration < 0L || candidateGeneration != priorGeneration + 1L) {
                throw new IllegalArgumentException("invalid population handle generations");
            }
        }

        public boolean matches(
                BondedVesselOperationRecord operation,
                BondedVesselBindingRecord binding
        ) {
            return operationId.equals(operation.operationId())
                    && bindingId.equals(operation.bindingId())
                    && bindingId.equals(binding.bindingId())
                    && profileId.equals(operation.profileId())
                    && profileId.equals(binding.profileId())
                    && priorGeneration == operation.priorGeneration()
                    && candidateGeneration == operation.candidateGeneration();
        }
    }

    public enum PopulationPreparationStatus { PREPARED, TERMINAL_DENIED, INDETERMINATE }

    public record PopulationPreparation(
            @Nonnull PopulationPreparationStatus status,
            @Nonnull String reason,
            @Nullable PopulationHandle handle
    ) {
        public PopulationPreparation {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if ((status == PopulationPreparationStatus.PREPARED) != (handle != null)) {
                throw new IllegalArgumentException("only PREPARED exposes a population handle");
            }
        }
    }

    public enum PopulationClaimStatus { CLAIMED, TERMINAL_DENIED, INDETERMINATE }

    public record PopulationClaim(
            @Nonnull PopulationClaimStatus status,
            @Nonnull String reason,
            @Nullable PopulationHandle handle
    ) {
        public PopulationClaim {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if ((status == PopulationClaimStatus.CLAIMED) != (handle != null)) {
                throw new IllegalArgumentException("only CLAIMED exposes a population handle");
            }
        }
    }

    public record WorldMutationRequest(
            @Nonnull PopulationMutationRequest populationRequest,
            @Nonnull PopulationHandle claimedPopulation
    ) {
        public WorldMutationRequest {
            populationRequest = Objects.requireNonNull(populationRequest, "populationRequest");
            claimedPopulation = Objects.requireNonNull(claimedPopulation, "claimedPopulation");
        }
    }

    public enum WorldMutationStatus {
        APPLIED, ALREADY_APPLIED, TERMINAL_DENIED, INDETERMINATE, QUARANTINED
    }

    public record WorldMutationReceipt(
            @Nonnull WorldMutationStatus status,
            @Nonnull String reason,
            @Nullable UUID activeNpcUuid,
            @Nullable BondedVesselBindingRecord.PhysicalLocation activeLocation,
            @Nullable String itemEvidenceJson
    ) {
        public WorldMutationReceipt {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            itemEvidenceJson = normalizeEvidence(itemEvidenceJson);
        }
    }

    public enum PopulationCommitStatus {
        APPLIED, ALREADY_APPLIED, TERMINAL_DENIED, INDETERMINATE, QUARANTINED
    }

    public record PopulationCommit(
            @Nonnull PopulationCommitStatus status,
            @Nonnull String reason,
            long committedProfileRevision,
            @Nullable UUID activeNpcUuid,
            @Nullable BondedVesselBindingRecord.PhysicalLocation activeLocation,
            @Nullable String itemEvidenceJson
    ) {
        public PopulationCommit {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if (committedProfileRevision < 0L) {
                throw new IllegalArgumentException("committedProfileRevision cannot be negative");
            }
            itemEvidenceJson = normalizeEvidence(itemEvidenceJson);
        }
    }

    public record ProfileReadiness(boolean canonicalReadReady, @Nonnull String reason) {
        public ProfileReadiness {
            reason = requireText(reason, "reason");
        }
    }

    public record PopulationReadiness(
            boolean ownerAdmissionReady,
            boolean claimAdmissionReady,
            boolean groupAdmissionReady,
            boolean atomicCommitReady,
            boolean recoveryReady,
            @Nonnull String reason
    ) {
        public PopulationReadiness {
            reason = requireText(reason, "reason");
        }

        public static PopulationReadiness unavailable(String reason) {
            return new PopulationReadiness(false, false, false, false, false, reason);
        }
    }

    public record WorldReadiness(
            boolean leaseBoundDispatchReady,
            boolean roleProjectionReady,
            boolean recoveryReady,
            @Nonnull String reason
    ) {
        public WorldReadiness {
            reason = requireText(reason, "reason");
        }

        public static WorldReadiness unavailable(String reason) {
            return new WorldReadiness(false, false, false, reason);
        }
    }

    /** All facets are required; composition must not advertise BONDED_VESSELS otherwise. */
    public record Readiness(
            boolean canonicalProfileReady,
            boolean ownerAdmissionReady,
            boolean claimAdmissionReady,
            boolean groupAdmissionReady,
            boolean atomicCommitReady,
            boolean populationRecoveryReady,
            boolean leaseBoundWorldDispatchReady,
            boolean roleProjectionReady,
            boolean worldRecoveryReady,
            boolean capabilityReady,
            @Nonnull String reason
    ) {
        public Readiness {
            reason = requireText(reason, "reason");
            boolean all = canonicalProfileReady && ownerAdmissionReady && claimAdmissionReady
                    && groupAdmissionReady && atomicCommitReady && populationRecoveryReady
                    && leaseBoundWorldDispatchReady && roleProjectionReady && worldRecoveryReady;
            if (capabilityReady != all) {
                throw new IllegalArgumentException("capability readiness must equal all mutation facets");
            }
        }
    }
}
