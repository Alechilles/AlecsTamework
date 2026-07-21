package com.alechilles.alecstamework.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransition;
import com.alechilles.alecstamework.api.Vector3View;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Internal-only unified owner/group/profile authority used by provisioning.
 *
 * <p>The dormant path deliberately has no NPC, destination, or claim token. Implementations must
 * reserve owner and group-owned deltas beneath the same lock/journal used by normal admission,
 * then create the canonical profile in the commit boundary. This type is never exposed by the
 * public API; public V1/V2 admission records continue rejecting {@code PROVISION_DORMANT}.</p>
 */
public interface ProvisioningPopulationBackend {
    @Nonnull
    PolicyResolution resolvePolicy(@Nonnull String roleId, long requestedRevision);

    @Nonnull
    CompletionStage<AdmissionPreparation> prepareDormant(@Nonnull DormantRequest request);

    @Nonnull
    ClaimResult claimDormant(@Nonnull UUID populationOperationId);

    @Nonnull
    CompletionStage<DormantCommit> commitDormant(@Nonnull UUID populationOperationId,
                                                  @Nonnull DormantProfileDraft profile);

    @Nonnull
    CompletionStage<Void> cancelDormant(@Nonnull UUID populationOperationId,
                                         @Nonnull String reason);

    @Nonnull
    CompletionStage<AdmissionPreparation> prepareActive(@Nonnull ActiveRequest request);

    @Nonnull
    ClaimResult claimActive(@Nonnull UUID populationOperationId);

    @Nonnull
    CompletionStage<ProfileSnapshot> commitActive(@Nonnull UUID populationOperationId);

    @Nonnull
    CompletionStage<Void> cancelActive(@Nonnull UUID populationOperationId,
                                        @Nonnull String reason);

    @Nonnull
    CompletionStage<TransitionOutcome> transition(@Nonnull TransitionRequest request);

    @Nonnull
    Optional<ProfileSnapshot> findProfile(@Nonnull String profileId);

    record PolicyResolution(boolean available, boolean matched, long revision,
                            @Nonnull String reason) {
        public PolicyResolution {
            reason = requireText(reason, "reason");
            if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
        }
    }

    record DormantRequest(@Nonnull UUID provisioningOperationId,
                          @Nonnull String provisionalProfileId,
                          @Nonnull UUID ownerUuid,
                          @Nonnull String roleId,
                          @Nonnull String ownershipWorldName,
                          long policyRevision) {
        public DormantRequest {
            provisioningOperationId = Objects.requireNonNull(provisioningOperationId, "provisioningOperationId");
            provisionalProfileId = requireText(provisionalProfileId, "provisionalProfileId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            roleId = requireText(roleId, "roleId");
            ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName");
            if (policyRevision < 0L) throw new IllegalArgumentException("policyRevision cannot be negative");
        }
    }

    record DormantProfileDraft(@Nonnull String provisionalProfileId,
                               @Nonnull UUID ownerUuid,
                               @Nonnull String roleId,
                               @Nonnull String ownershipWorldName,
                               @Nullable String displayName,
                               @Nullable Vector3View homePosition) {
        public DormantProfileDraft {
            provisionalProfileId = requireText(provisionalProfileId, "provisionalProfileId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            roleId = requireText(roleId, "roleId");
            ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName");
            displayName = normalize(displayName);
        }
    }

    record ActiveRequest(@Nonnull UUID provisioningOperationId,
                         @Nonnull String profileId,
                         @Nonnull UUID ownerUuid,
                         @Nonnull String roleId,
                         @Nonnull String ownershipWorldName,
                         @Nonnull PopulationAdmissionLocation destination,
                         long expectedProfileRevision) {
        public ActiveRequest {
            provisioningOperationId = Objects.requireNonNull(provisioningOperationId, "provisioningOperationId");
            profileId = requireText(profileId, "profileId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            roleId = requireText(roleId, "roleId");
            ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName");
            destination = Objects.requireNonNull(destination, "destination");
            if (expectedProfileRevision < 0L) {
                throw new IllegalArgumentException("expectedProfileRevision cannot be negative");
            }
        }
    }

    record TransitionRequest(@Nonnull UUID operationId,
                             @Nonnull String callerNamespace,
                             @Nonnull String idempotencyKey,
                             @Nonnull UUID actorUuid,
                             @Nonnull String profileId,
                             long expectedProfileRevision,
                             @Nonnull ProvisionedCompanionTransition transition,
                             @Nonnull String ownershipWorldName,
                             @Nullable PopulationAdmissionLocation destination) {
        public TransitionRequest {
            operationId = Objects.requireNonNull(operationId, "operationId");
            callerNamespace = requireText(callerNamespace, "callerNamespace");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
            profileId = requireText(profileId, "profileId");
            transition = Objects.requireNonNull(transition, "transition");
            ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName");
            if (expectedProfileRevision < 0L) {
                throw new IllegalArgumentException("expectedProfileRevision cannot be negative");
            }
        }
    }

    record AdmissionPreparation(Status status, @Nonnull String reason,
                                @Nullable UUID populationOperationId,
                                @Nullable PopulationAdmissionDecision populationDecision) {
        public AdmissionPreparation {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if (status == Status.PREPARED && populationOperationId == null) {
                throw new IllegalArgumentException("Prepared admission requires an operation id");
            }
        }
        public enum Status { PREPARED, DENIED, UNAVAILABLE, QUARANTINED }
    }

    record ClaimResult(boolean claimed, @Nonnull String reason,
                       @Nullable PopulationAdmissionDecision populationDecision) {
        public ClaimResult { reason = requireText(reason, "reason"); }
    }

    record DormantCommit(Status status, @Nonnull String reason,
                         @Nullable ProfileSnapshot profile,
                         @Nullable PopulationAdmissionDecision populationDecision) {
        public DormantCommit {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if (status == Status.COMMITTED && profile == null) {
                throw new IllegalArgumentException("Committed dormant admission requires a profile");
            }
        }
        public enum Status { COMMITTED, DENIED, UNAVAILABLE, QUARANTINED }
    }

    record TransitionOutcome(Status status, @Nonnull String reason,
                             @Nullable ProfileSnapshot profile,
                             @Nullable PopulationAdmissionDecision populationDecision) {
        public TransitionOutcome {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if ((status == Status.COMMITTED || status == Status.IDEMPOTENT) && profile == null) {
                throw new IllegalArgumentException("Successful transition requires a profile");
            }
        }
        public enum Status { COMMITTED, IDEMPOTENT, DENIED, UNAVAILABLE, QUARANTINED }
    }

    record ProfileSnapshot(@Nonnull String profileId,
                           @Nonnull UUID ownerUuid,
                           @Nonnull String roleId,
                           @Nonnull PopulationCompanionLifecycle lifecycle,
                           @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
                           @Nullable UUID currentNpcUuid,
                           long profileRevision,
                           long updatedAtMs) {
        public ProfileSnapshot {
            profileId = requireText(profileId, "profileId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            roleId = requireText(roleId, "roleId");
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            projectionStatus = Objects.requireNonNull(projectionStatus, "projectionStatus");
            if (profileRevision < 0L || updatedAtMs < 0L) {
                throw new IllegalArgumentException("Profile revision/timestamp cannot be negative");
            }
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
