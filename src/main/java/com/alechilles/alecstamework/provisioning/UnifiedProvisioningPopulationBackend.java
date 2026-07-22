package com.alechilles.alecstamework.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationLimitScope;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.ownership.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupTransition;
import com.alechilles.alecstamework.ownership.groups.runtime.PopulationGroupAdmissionRuntime;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningCommandLinkRepository;
import com.alechilles.alecstamework.persistence.sqlite.CommandFamilyRosterRepository;
import com.alechilles.alecstamework.persistence.sqlite.UnifiedPopulationCompositeStore;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Concrete owner/group/profile backend for restart-safe companion provisioning. */
public final class UnifiedProvisioningPopulationBackend implements ProvisioningPopulationBackend {
    private static final String SOURCE = "companion_provisioning";
    private final OwnerPopulationRuntime ownerRuntime;
    private final PopulationGroupRegistry groupRegistry;
    private final PopulationGroupRepository groupRepository;
    private final NpcProfileRepository profileRepository;
    private final PopulationGroupAdmissionRuntime groupRuntime;
    private final ProvisionedCompanionProjectionPort projectionPort;
    @Nullable private final CompanionProvisioningCommandLinkRepository commandLinkRepository;
    @Nullable private final CommandFamilyRosterRepository commandFamilyRosterRepository;
    @Nullable private final CommandFamilyRosterRepository.ProfilePolicyFence commandFamilyPolicyFence;
    private final ConcurrentHashMap<UUID, DormantRequest> dormantRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> alreadyCommitted = new ConcurrentHashMap<>();
    private final AtomicBoolean recoveryReady = new AtomicBoolean(false);

    public UnifiedProvisioningPopulationBackend(
            @Nonnull OwnerPopulationRuntime ownerRuntime,
            @Nonnull PopulationGroupRegistry groupRegistry,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull NpcProfileRepository profileRepository,
            @Nonnull ProvisionedCompanionProjectionPort projectionPort) {
        this.ownerRuntime = Objects.requireNonNull(ownerRuntime, "ownerRuntime");
        this.groupRegistry = Objects.requireNonNull(groupRegistry, "groupRegistry");
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.projectionPort = Objects.requireNonNull(projectionPort, "projectionPort");
        this.commandLinkRepository = null;
        this.commandFamilyRosterRepository = null;
        this.commandFamilyPolicyFence = null;
        this.groupRuntime = new PopulationGroupAdmissionRuntime(
                ownerRuntime.admissionCoordinator(), groupRegistry, groupRepository, profileRepository);
    }

    /** Full constructor enabling atomic dormant profile plus command-family membership commits. */
    public UnifiedProvisioningPopulationBackend(
            @Nonnull OwnerPopulationRuntime ownerRuntime,
            @Nonnull PopulationGroupRegistry groupRegistry,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull NpcProfileRepository profileRepository,
            @Nonnull ProvisionedCompanionProjectionPort projectionPort,
            @Nonnull CompanionProvisioningCommandLinkRepository commandLinkRepository,
            @Nonnull CommandFamilyRosterRepository commandFamilyRosterRepository,
            @Nonnull CommandFamilyRosterRepository.ProfilePolicyFence commandFamilyPolicyFence) {
        this.ownerRuntime = Objects.requireNonNull(ownerRuntime, "ownerRuntime");
        this.groupRegistry = Objects.requireNonNull(groupRegistry, "groupRegistry");
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.projectionPort = Objects.requireNonNull(projectionPort, "projectionPort");
        this.commandLinkRepository = Objects.requireNonNull(commandLinkRepository, "commandLinkRepository");
        this.commandFamilyRosterRepository = Objects.requireNonNull(
                commandFamilyRosterRepository, "commandFamilyRosterRepository");
        this.commandFamilyPolicyFence = Objects.requireNonNull(
                commandFamilyPolicyFence, "commandFamilyPolicyFence");
        this.groupRuntime = new PopulationGroupAdmissionRuntime(
                ownerRuntime.admissionCoordinator(), groupRegistry, groupRepository, profileRepository);
    }

    public UnifiedProvisioningPopulationBackend(
            @Nonnull OwnerPopulationRuntime ownerRuntime,
            @Nonnull PopulationGroupRegistry groupRegistry,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull NpcProfileRepository profileRepository) {
        this(ownerRuntime, groupRegistry, groupRepository, profileRepository,
                ProvisionedCompanionProjectionPort.unavailable());
    }

    @Override
    public PolicyResolution resolvePolicy(String roleId, long requestedRevision) {
        String role = requireText(roleId, "roleId");
        var index = groupRegistry.snapshot();
        if (requestedRevision >= 0L && requestedRevision != index.revision()) {
            return new PolicyResolution(true, false, index.revision(),
                    "population-group-policy-revision-mismatch");
        }
        if (index.resolveForRole(role).isEmpty()) {
            return new PolicyResolution(true, false, index.revision(),
                    "population-group-role-unresolved");
        }
        OwnerPolicy policy = ownerPolicy();
        if (!policy.available()) {
            return new PolicyResolution(false, false, index.revision(), policy.reason());
        }
        return new PolicyResolution(true, true, index.revision(), "population-policy-resolved");
    }

    @Override
    public CompletionStage<AdmissionPreparation> prepareDormant(DormantRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<ProfileSnapshot> committed = findProfile(request.provisionalProfileId());
        if (committed.isPresent()) {
            ProfileSnapshot profile = committed.get();
            if (profile.ownerUuid().equals(request.ownerUuid())
                    && profile.roleId().equals(request.roleId())
                    && profile.lifecycle() == PopulationCompanionLifecycle.PROVISIONED_DORMANT) {
                alreadyCommitted.put(request.provisioningOperationId(), Boolean.TRUE);
                dormantRequests.put(request.provisioningOperationId(), request);
                return CompletableFuture.completedFuture(new AdmissionPreparation(
                        AdmissionPreparation.Status.PREPARED, "provisioned-dormant-already-committed",
                        request.provisioningOperationId(), null));
            }
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.QUARANTINED,
                    "provisioned-dormant-profile-conflict", null, null));
        }

        var index = groupRegistry.snapshot();
        if (index.revision() != request.policyRevision()) {
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.DENIED,
                    "population-group-policy-revision-changed", null, null));
        }
        List<String> groupIds = index.resolveForRole(request.roleId()).stream()
                .map(definition -> definition.groupId()).sorted().toList();
        if (groupIds.isEmpty()) {
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.DENIED,
                    "population-group-role-unresolved", null, null));
        }
        OwnerPolicy ownerPolicy = ownerPolicy();
        if (!ownerPolicy.available()) {
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.UNAVAILABLE, ownerPolicy.reason(), null, null));
        }

        OwnerPopulationEntry current = ownerRuntime.index()
                .entry(request.provisionalProfileId()).orElse(null);
        if (current != null && current.ownerId() != null) {
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.QUARANTINED,
                    "provisioned-dormant-owner-conflict", null, null));
        }
        long nowMs = System.currentTimeMillis();
        long expectedRevision = current == null
                ? OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION : current.revision();
        CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                request.provisionalProfileId(), null, null, request.ownershipWorldName(),
                current == null ? request.ownershipWorldName() : current.ownershipWorldName(),
                current == null ? CompanionLifecycleState.UNKNOWN_DORMANT.name()
                        : current.lifecycleState().name(),
                null, null, null, current == null ? 0L : current.revision(),
                SOURCE, nowMs, nowMs);
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                request.provisionalProfileId(), expectedRevision, null,
                current == null ? null : current.ownershipWorldName(), request.ownerUuid(),
                request.ownershipWorldName(), CompanionLifecycleState.PROVISIONED_DORMANT,
                OwnerPopulationOperation.NEW_OWNERSHIP, ownerPolicy.scope(), ownerPolicy.limit(), false);
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                transition, baseline, null, null, null, null, SOURCE,
                ownerJson(null), ownerJson(request.ownerUuid()),
                contextJson(request.provisioningOperationId()), ownerPolicy.settingsRevision(),
                ClaimProviderGeneration.NONE);
        List<String> oldGroups = current == null ? List.of() : Optional.ofNullable(
                safeClassification(request.provisionalProfileId()))
                .map(PopulationGroupClassificationRecord::groupIds).orElse(List.of());
        String oldRole = current == null ? null : Optional.ofNullable(
                profileRepository.loadProfileById(request.provisionalProfileId()))
                .map(NpcProfileRepository.ProfileRecord::roleId).orElse(null);
        PopulationGroupTransition groupTransition = new PopulationGroupTransition(
                null, oldRole, current == null ? null : current.ownershipWorldName(),
                current == null ? null : current.lifecycleState(),
                request.ownerUuid(), request.roleId(), request.ownershipWorldName(),
                CompanionLifecycleState.PROVISIONED_DORMANT);
        String groupOperationId = nextGroupOperationId(request.provisioningOperationId());
        PopulationGroupOperationRecord groupOperation = new PopulationGroupOperationRecord(
                groupOperationId, request.provisioningOperationId().toString(),
                request.provisionalProfileId(), "PROVISION_DORMANT",
                PopulationGroupOperationRecord.State.PREPARED, baseline.revision(),
                request.policyRevision(), null, request.ownerUuid(), oldRole, request.roleId(),
                oldGroups, groupIds, current == null ? null : current.lifecycleState().name(),
                CompanionLifecycleState.PROVISIONED_DORMANT.name(),
                current == null ? null : current.ownershipWorldName(), request.ownershipWorldName(),
                null, "PREPARING", nowMs, nowMs, 0L);
        PopulationGroupClassificationRecord existingClassification =
                safeClassification(request.provisionalProfileId());
        PopulationGroupClassificationRecord classification =
                new PopulationGroupClassificationRecord(
                        request.provisionalProfileId(), request.roleId(), groupIds,
                        request.policyRevision(), PopulationGroupClassificationRecord.Status.RESOLVED,
                        SOURCE, existingClassification == null ? nowMs
                        : existingClassification.createdAtMs(), nowMs);
        PopulationGroupRepository.ClassificationMutation classificationMutation =
                new PopulationGroupRepository.ClassificationMutation(
                        existingClassification == null ? null
                                : existingClassification.classificationRevision(), classification);
        PopulationGroupAdmissionRuntime.Request groupRequest =
                new PopulationGroupAdmissionRuntime.Request(
                        request.provisioningOperationId(), ownerPlan, groupTransition,
                        groupOperation, classificationMutation, request.policyRevision(),
                        ownerPolicy.settingsRevision(), ClaimProviderGeneration.NONE);
        return groupRuntime.prepare(groupRequest).thenApply(preparation -> {
            if (!preparation.prepared()) {
                return new AdmissionPreparation(AdmissionPreparation.Status.DENIED,
                        preparation.reason(), null, null);
            }
            dormantRequests.put(request.provisioningOperationId(), request);
            return new AdmissionPreparation(AdmissionPreparation.Status.PREPARED,
                    preparation.reason(), preparation.populationOperationId(), null);
        });
    }

    @Override
    public CompletionStage<AdmissionPreparation> resumeDormant(
            DormantRequest request, UUID previousPopulationOperationId) {
        Objects.requireNonNull(previousPopulationOperationId, "previousPopulationOperationId");
        return prepareDormant(request);
    }

    @Override
    public ClaimResult claimDormant(UUID populationOperationId) {
        if (alreadyCommitted.containsKey(populationOperationId)) {
            return new ClaimResult(true, "provisioned-dormant-already-committed", null);
        }
        OwnerPolicy policy = ownerPolicy();
        if (!policy.available()) return new ClaimResult(false, policy.reason(), null);
        PopulationGroupAdmissionRuntime.Claim claim = groupRuntime.claim(
                populationOperationId, policy.settingsRevision(),
                groupRegistry.snapshot().revision(), ClaimProviderGeneration.NONE);
        return new ClaimResult(claim.claimed(), claim.reason(), null);
    }

    @Override
    public CompletionStage<DormantCommit> commitDormant(
            UUID populationOperationId, DormantProfileDraft profile) {
        Objects.requireNonNull(profile, "profile");
        DormantRequest request = dormantRequests.get(populationOperationId);
        if (request == null || !sameDraft(request, profile)) {
            return CompletableFuture.completedFuture(new DormantCommit(
                    DormantCommit.Status.QUARANTINED,
                    "provisioned-dormant-draft-mismatch", null, null));
        }
        if (alreadyCommitted.remove(populationOperationId) != null) {
            return CompletableFuture.completedFuture(findProfile(profile.provisionalProfileId())
                    .map(snapshot -> new DormantCommit(DormantCommit.Status.COMMITTED,
                            "provisioned-dormant-already-committed", snapshot, null))
                    .orElseGet(() -> new DormantCommit(DormantCommit.Status.QUARANTINED,
                            "provisioned-dormant-profile-missing", null, null)));
        }
        long nowMs = System.currentTimeMillis();
        NpcProfileRepository.DormantProfileMutation mutation =
                new NpcProfileRepository.DormantProfileMutation(
                        profile.provisionalProfileId(), profile.ownerUuid(), profile.roleId(),
                        profile.ownershipWorldName(), profile.displayName(), initialJson(profile), nowMs);
        UnifiedPopulationCompositeStore.ProvisionedDormantCommitExtension extension =
                commandLinkRepository == null
                        ? UnifiedPopulationCompositeStore.ProvisionedDormantCommitExtension.NO_OP
                        : (connection, committedProfile) -> {
                            CompanionProvisioningCommandLinkRepository.CommitResult linked =
                                    commandLinkRepository.commitInTransaction(
                                            connection, request.provisioningOperationId(),
                                            committedProfile.profileId(), commandFamilyRosterRepository,
                                            commandFamilyPolicyFence);
                            return linked.committed()
                                    ? UnifiedPopulationCompositeStore.ExtensionResult.success()
                                    : UnifiedPopulationCompositeStore.ExtensionResult.denied(
                                            linked.reason() == null
                                                    ? "provisioning-command-link-denied" : linked.reason());
                        };
        return groupRuntime.commitDormant(populationOperationId, mutation, nowMs, extension)
                .thenApply(committed -> {
                    if (!committed.committed()) {
                        return new DormantCommit(DormantCommit.Status.QUARANTINED,
                                committed.reason(), null, null);
                    }
                    dormantRequests.remove(populationOperationId);
                    return findProfile(profile.provisionalProfileId())
                            .map(snapshot -> new DormantCommit(DormantCommit.Status.COMMITTED,
                                    committed.reason(), snapshot, null))
                            .orElseGet(() -> new DormantCommit(DormantCommit.Status.QUARANTINED,
                                    "provisioned-dormant-profile-missing-after-commit", null, null));
                });
    }

    @Override
    public CompletionStage<Void> cancelDormant(UUID populationOperationId, String reason) {
        dormantRequests.remove(populationOperationId);
        alreadyCommitted.remove(populationOperationId);
        return groupRuntime.cancel(populationOperationId, reason);
    }

    @Override
    public CompletionStage<AdmissionPreparation> prepareActive(ActiveRequest request) {
        return projectionPort.prepare(request);
    }

    @Override
    public CompletionStage<AdmissionPreparation> resumeActive(
            ActiveRequest request, UUID previousPopulationOperationId) {
        return projectionPort.resume(request, previousPopulationOperationId);
    }

    @Override public ClaimResult claimActive(UUID populationOperationId) {
        return projectionPort.claim(populationOperationId);
    }

    @Override
    public CompletionStage<ProfileSnapshot> commitActive(UUID populationOperationId) {
        return projectionPort.commit(populationOperationId);
    }

    @Override
    public CompletionStage<Void> cancelActive(UUID populationOperationId, String reason) {
        return projectionPort.cancel(populationOperationId, reason);
    }

    @Override
    public CompletionStage<TransitionOutcome> transition(TransitionRequest request) {
        return projectionPort.transition(request);
    }

    @Override
    public Optional<ProfileSnapshot> findProfile(String profileId) {
        String normalized = requireText(profileId, "profileId");
        OwnerPopulationEntry owner = ownerRuntime.index().entry(normalized).orElse(null);
        NpcProfileRepository.ProfileRecord profile = profileRepository.loadProfileById(normalized);
        if (owner == null || owner.ownerId() == null || profile == null || profile.roleId() == null) {
            return Optional.empty();
        }
        PopulationCompanionLifecycle lifecycle = PopulationCompanionLifecycle.valueOf(
                owner.lifecycleState().name());
        CompanionProvisioningProjectionStatus projection = switch (lifecycle) {
            case ACTIVE, UNLOADED, RESTORING -> CompanionProvisioningProjectionStatus.ACTIVE;
            case PROVISIONED_DORMANT -> CompanionProvisioningProjectionStatus.NOT_REQUESTED;
            default -> CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE;
        };
        return Optional.of(new ProfileSnapshot(
                normalized, owner.ownerId(), profile.roleId(), lifecycle, projection,
                profile.currentNpcUuid(), owner.revision(), profile.updatedAtMs()));
    }

    @Nonnull
    public CompletionStage<PopulationGroupAdmissionRuntime.RecoveryReport> recover() {
        recoveryReady.set(false);
        return groupRuntime.recover().thenApply(report -> {
            recoveryReady.set(report.ready());
            return report;
        });
    }

    public boolean dormantReady() {
        OwnerPolicy policy = ownerPolicy();
        return policy.available() && !groupRegistry.snapshot().definitions().isEmpty();
    }

    public boolean activeProjectionReady() { return projectionPort.available(); }
    public boolean recoveryReady() { return recoveryReady.get() && groupRuntime.recoveryReady(); }

    @Nonnull
    public Readiness readiness() {
        return new Readiness(dormantReady(), activeProjectionReady(), recoveryReady(),
                dormantReady() ? (projectionPort.available() ? "ready" : "active-projection-unavailable")
                        : "dormant-authority-unavailable");
    }

    private OwnerPolicy ownerPolicy() {
        var diagnostics = ownerRuntime.populationPolicyAuthority().populationDiagnostics();
        var rules = diagnostics.activeRules();
        if (rules.ownerLimit() < 0) {
            return OwnerPolicy.unavailable("owner-population-policy-unavailable");
        }
        OwnerPopulationLimitScope scope;
        try {
            scope = OwnerPopulationLimitScope.valueOf(rules.ownerScope());
        } catch (RuntimeException invalid) {
            return OwnerPolicy.unavailable("owner-population-scope-unavailable");
        }
        String readiness = scope == OwnerPopulationLimitScope.GLOBAL
                ? diagnostics.readiness().ownerGlobal() : diagnostics.readiness().ownerPerWorld();
        if (!"READY".equals(readiness)) {
            return OwnerPolicy.unavailable("owner-population-not-ready");
        }
        long settingsRevision = diagnostics.claimLookups().provider() == null
                ? 0L : diagnostics.claimLookups().provider().settingsRevision();
        return new OwnerPolicy(true, Math.max(0, rules.ownerLimit()), scope,
                settingsRevision, "owner-population-ready");
    }

    @Nullable
    private PopulationGroupClassificationRecord safeClassification(String profileId) {
        try {
            return groupRepository.findClassification(profileId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String nextGroupOperationId(UUID populationOperationId) {
        int attempt = 0;
        try {
            attempt = groupRepository.loadOperationsByPopulationOperationId(
                    populationOperationId.toString()).size();
        } catch (Exception ignored) {
            // The write transaction still detects an operation-id conflict and fails closed.
        }
        return UUID.nameUUIDFromBytes((populationOperationId + ":groups:" + attempt)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean sameDraft(DormantRequest request, DormantProfileDraft draft) {
        return request.provisionalProfileId().equals(draft.provisionalProfileId())
                && request.ownerUuid().equals(draft.ownerUuid())
                && request.roleId().equals(draft.roleId())
                && request.ownershipWorldName().equals(draft.ownershipWorldName());
    }

    private static String ownerJson(@Nullable UUID ownerUuid) {
        JsonObject json = new JsonObject();
        if (ownerUuid == null) json.add("ownerUuid", null);
        else json.addProperty("ownerUuid", ownerUuid.toString());
        return json.toString();
    }

    private static String contextJson(UUID provisioningOperationId) {
        JsonObject json = new JsonObject();
        json.addProperty("operation", "provision_dormant");
        json.addProperty("provisioningOperationId", provisioningOperationId.toString());
        return json.toString();
    }

    private static String initialJson(DormantProfileDraft draft) {
        JsonObject json = new JsonObject();
        if (draft.displayName() != null) json.addProperty("displayName", draft.displayName());
        if (draft.homePosition() != null) {
            JsonObject home = new JsonObject();
            home.addProperty("x", draft.homePosition().x());
            home.addProperty("y", draft.homePosition().y());
            home.addProperty("z", draft.homePosition().z());
            json.add("homePosition", home);
        }
        return json.toString();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private record OwnerPolicy(boolean available, int limit,
                               @Nonnull OwnerPopulationLimitScope scope,
                               long settingsRevision, @Nonnull String reason) {
        static OwnerPolicy unavailable(String reason) {
            return new OwnerPolicy(false, 0, OwnerPopulationLimitScope.GLOBAL, 0L, reason);
        }
    }

    public record Readiness(boolean dormantReady, boolean activeProjectionReady,
                            boolean recoveryReady, @Nonnull String reason) {
    }
}
