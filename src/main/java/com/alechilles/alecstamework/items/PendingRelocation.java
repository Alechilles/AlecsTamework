package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.ownership.CompanionRelocationAdmissionService;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Mutable concurrency-safe state for one queued command relocation. */
final class PendingRelocation {
    final UUID npcUuid;
    final Vector3d destination;
    final String destinationWorldName;
    final Vector3d sourceHintPosition;
    final Vector3d alternateSourceHintPosition;
    @Nullable
    final ClaimChunkCoordinate canonicalSource;
    final UUID ownerUuid;
    final boolean assignOwnerAsMasterTarget;
    final boolean clearLockedTarget;
    final String state;
    final String subState;
    final long executeAfterMs;
    final long queuedAtMs;
    final boolean allowCrossWorldTransfer;
    final TwCompanionConfig.TransferFailurePolicy onTransferFailure;
    final CompanionRelocationAdmissionService.ForcePolicy forcePolicy;
    private final Set<String> requiredStateFilter;
    private final ConcurrentHashMap<ChunkRequestKey, Long> lastChunkRequestAtMsByChunk =
            new ConcurrentHashMap<>();
    long nextScheduledApplyAtMs = Long.MAX_VALUE;
    boolean relocationIssued;
    long relocationIssuedAtMs;
    private boolean physicalMutationAttempted;
    private boolean crossWorldDestinationInstalled;
    private boolean crossWorldTransferAttempted;
    int retryAttempts;
    int lastLoggedRetryAttempts;
    long lastRetryCountedAtMs;
    private boolean crossWorldTransferInProgress;
    private boolean sourceWorldMissingLogged;
    private AdmissionPhase admissionPhase = AdmissionPhase.NONE;
    private CompanionRelocationAdmissionService.Admission admission;
    private boolean cancellationRequestedDuringClaim;

    PendingRelocation(UUID npcUuid,
                      Vector3d destination,
                      String destinationWorldName,
                      Vector3d sourceHintPosition,
                      Vector3d alternateSourceHintPosition,
                      @Nullable ClaimChunkCoordinate canonicalSource,
                      UUID ownerUuid,
                      boolean assignOwnerAsMasterTarget,
                      boolean clearLockedTarget,
                      String state,
                      String subState,
                      long executeAfterMs,
                      long queuedAtMs,
                      boolean allowCrossWorldTransfer,
                      @Nullable TwCompanionConfig.TransferFailurePolicy onTransferFailure,
                      @Nullable String[] requiredStateFilter,
                      CompanionRelocationAdmissionService.ForcePolicy forcePolicy) {
        this.npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.destinationWorldName = Objects.requireNonNull(
                destinationWorldName, "destinationWorldName"
        );
        this.sourceHintPosition = sourceHintPosition;
        this.alternateSourceHintPosition = alternateSourceHintPosition;
        this.canonicalSource = canonicalSource;
        this.ownerUuid = ownerUuid;
        this.assignOwnerAsMasterTarget = assignOwnerAsMasterTarget;
        this.clearLockedTarget = clearLockedTarget;
        this.state = state;
        this.subState = subState;
        this.executeAfterMs = executeAfterMs;
        this.queuedAtMs = queuedAtMs;
        this.allowCrossWorldTransfer = allowCrossWorldTransfer;
        this.onTransferFailure = onTransferFailure == null
                ? TwCompanionConfig.TransferFailurePolicy.QueueForRecall : onTransferFailure;
        this.requiredStateFilter = normalizeStateFilter(requiredStateFilter);
        this.forcePolicy = Objects.requireNonNull(forcePolicy, "forcePolicy");
        this.lastRetryCountedAtMs = queuedAtMs;
    }

    boolean shouldRequestChunk(String worldName,
                               int chunkX,
                               int chunkZ,
                               long nowMs,
                               long cooldownMs) {
        ChunkRequestKey chunkKey = new ChunkRequestKey(worldName, chunkX, chunkZ);
        Long lastRequestAtMs = lastChunkRequestAtMsByChunk.get(chunkKey);
        if (lastRequestAtMs != null && nowMs - lastRequestAtMs < cooldownMs) {
            return false;
        }
        lastChunkRequestAtMsByChunk.put(chunkKey, nowMs);
        return true;
    }

    private record ChunkRequestKey(String worldName, int chunkX, int chunkZ) {
        private ChunkRequestKey {
            worldName = Objects.requireNonNull(worldName, "worldName");
        }
    }

    synchronized boolean reserveScheduledApply(long dueAtMs) {
        if (dueAtMs >= nextScheduledApplyAtMs) {
            return false;
        }
        nextScheduledApplyAtMs = dueAtMs;
        return true;
    }

    synchronized boolean consumeScheduledApply(long dueAtMs) {
        if (nextScheduledApplyAtMs != dueAtMs) {
            return false;
        }
        nextScheduledApplyAtMs = Long.MAX_VALUE;
        return true;
    }

    synchronized boolean markRetryProgressLogged(int retryStep) {
        if (retryStep <= 0 || retryAttempts <= 0 || retryAttempts % retryStep != 0
                || retryAttempts == lastLoggedRetryAttempts) {
            return false;
        }
        lastLoggedRetryAttempts = retryAttempts;
        return true;
    }

    synchronized void markRelocationIssued(long nowMs) {
        relocationIssued = true;
        relocationIssuedAtMs = nowMs;
        physicalMutationAttempted = true;
    }

    synchronized void resetRelocationIssue() {
        relocationIssued = false;
        relocationIssuedAtMs = 0L;
    }

    synchronized void markPhysicalMutationAttempted() {
        physicalMutationAttempted = true;
    }

    synchronized void markPhysicalMutationCompensated() {
        physicalMutationAttempted = false;
    }

    synchronized boolean physicalMutationAttempted() {
        return physicalMutationAttempted;
    }

    synchronized void markCrossWorldDestinationInstalled() {
        crossWorldDestinationInstalled = true;
    }

    synchronized boolean crossWorldDestinationInstalled() {
        return crossWorldDestinationInstalled;
    }

    synchronized boolean markCrossWorldTransferStarted() {
        if (crossWorldTransferInProgress) {
            return false;
        }
        crossWorldTransferAttempted = true;
        crossWorldTransferInProgress = true;
        return true;
    }

    synchronized boolean crossWorldTransferAttempted() {
        return crossWorldTransferAttempted;
    }

    synchronized void markCrossWorldTransferFinished() {
        crossWorldTransferInProgress = false;
    }

    synchronized boolean isCrossWorldTransferInProgress() {
        return crossWorldTransferInProgress;
    }

    synchronized boolean markSourceWorldMissingLogged() {
        if (sourceWorldMissingLogged) {
            return false;
        }
        sourceWorldMissingLogged = true;
        return true;
    }

    synchronized void resetSourceWorldMissingLogged() {
        sourceWorldMissingLogged = false;
    }

    boolean isStateAllowed(@Nullable String stateName) {
        if (requiredStateFilter.isEmpty()) {
            return true;
        }
        String normalizedState = normalizeStateKey(stateName);
        if (normalizedState == null) {
            return false;
        }
        for (String requiredState : requiredStateFilter) {
            if (matchesStateFilter(normalizedState, requiredState)) {
                return true;
            }
        }
        return false;
    }

    String describeStateFilter() {
        return requiredStateFilter.isEmpty() ? "[]" : requiredStateFilter.toString();
    }

    synchronized boolean beginAdmissionPreparation() {
        if (admissionPhase != AdmissionPhase.NONE) {
            return false;
        }
        admissionPhase = AdmissionPhase.PREPARING;
        return true;
    }

    synchronized boolean installReservedAdmission(CompanionRelocationAdmissionService.Decision decision) {
        if (admissionPhase != AdmissionPhase.PREPARING || decision == null
                || decision.status() != CompanionRelocationAdmissionService.Status.RESERVED
                || decision.admission() == null) {
            return false;
        }
        admission = decision.admission();
        admissionPhase = AdmissionPhase.RESERVED;
        return true;
    }

    synchronized void finishUnclaimedPreparation(boolean retry) {
        if (admissionPhase == AdmissionPhase.PREPARING) {
            admissionPhase = retry ? AdmissionPhase.NONE : AdmissionPhase.TERMINAL;
        }
    }

    synchronized boolean admissionPrepared() {
        return admissionPhase == AdmissionPhase.RESERVED || admissionPhase == AdmissionPhase.APPLYING;
    }

    synchronized boolean admissionReserved() {
        return admissionPhase == AdmissionPhase.RESERVED;
    }

    synchronized boolean admissionApplying() {
        return admissionPhase == AdmissionPhase.APPLYING;
    }

    synchronized boolean admissionTransitionInProgress() {
        return admissionPhase == AdmissionPhase.PREPARING
                || admissionPhase == AdmissionPhase.CLAIMING
                || admissionPhase == AdmissionPhase.CANCELING
                || admissionPhase == AdmissionPhase.COMMITTING;
    }

    @Nullable
    synchronized CompanionRelocationAdmissionService.Admission beginApplyClaim() {
        if (admissionPhase != AdmissionPhase.RESERVED || admission == null) {
            return null;
        }
        admissionPhase = AdmissionPhase.CLAIMING;
        return admission;
    }

    synchronized ClaimCompletion finishApplyClaim(
            CompanionRelocationAdmissionService.Admission claimedAdmission,
            CompanionRelocationAdmissionService.Decision decision,
            boolean retryable
    ) {
        if (admissionPhase != AdmissionPhase.CLAIMING || admission == null
                || !admission.equals(claimedAdmission)) {
            return ClaimCompletion.REJECTED;
        }
        if (decision == null || decision.status() != CompanionRelocationAdmissionService.Status.APPLYING
                || decision.admission() == null
                || !decision.admission().equals(claimedAdmission)) {
            admission = null;
            cancellationRequestedDuringClaim = false;
            admissionPhase = retryable ? AdmissionPhase.NONE : AdmissionPhase.TERMINAL;
            return retryable ? ClaimCompletion.RETRY_REQUIRED : ClaimCompletion.REJECTED;
        }
        if (cancellationRequestedDuringClaim) {
            admissionPhase = AdmissionPhase.CANCELING;
            return ClaimCompletion.CANCEL_REQUIRED;
        }
        admissionPhase = AdmissionPhase.APPLYING;
        physicalMutationAttempted = true;
        return ClaimCompletion.APPLYING;
    }

    @Nullable
    synchronized CompanionRelocationAdmissionService.Admission failApplyClaimForCancellation() {
        if (admissionPhase != AdmissionPhase.CLAIMING || admission == null) {
            return null;
        }
        admissionPhase = AdmissionPhase.CANCELING;
        return admission;
    }

    @Nullable
    synchronized CompanionRelocationAdmissionService.Admission beginCancellation() {
        if (admissionPhase == AdmissionPhase.CLAIMING) {
            cancellationRequestedDuringClaim = true;
            return null;
        }
        if ((admissionPhase != AdmissionPhase.RESERVED && admissionPhase != AdmissionPhase.APPLYING)
                || admission == null) {
            return null;
        }
        admissionPhase = AdmissionPhase.CANCELING;
        return admission;
    }

    synchronized void finishCancellation(boolean retry) {
        admission = null;
        cancellationRequestedDuringClaim = false;
        admissionPhase = retry ? AdmissionPhase.NONE : AdmissionPhase.TERMINAL;
    }

    @Nullable
    synchronized CompanionRelocationAdmissionService.Admission beginCommit() {
        if (admissionPhase != AdmissionPhase.APPLYING || admission == null) {
            return null;
        }
        admissionPhase = AdmissionPhase.COMMITTING;
        return admission;
    }

    synchronized boolean admissionCommitInProgress() {
        return admissionPhase == AdmissionPhase.COMMITTING;
    }

    synchronized void finishCommit() {
        admission = null;
        admissionPhase = AdmissionPhase.TERMINAL;
    }

    synchronized void terminateAdmission() {
        admission = null;
        cancellationRequestedDuringClaim = false;
        admissionPhase = AdmissionPhase.TERMINAL;
    }

    synchronized String admissionPhaseName() {
        return admissionPhase.name();
    }

    /** Treats repeated clicks for the same command as one request even if the player moved. */
    boolean hasSameCommandIntent(PendingRelocation other) {
        return other != null
                && Objects.equals(ownerUuid, other.ownerUuid)
                && Objects.equals(destinationWorldName, other.destinationWorldName)
                && assignOwnerAsMasterTarget == other.assignOwnerAsMasterTarget
                && clearLockedTarget == other.clearLockedTarget
                && Objects.equals(state, other.state)
                && Objects.equals(subState, other.subState)
                && allowCrossWorldTransfer == other.allowCrossWorldTransfer
                && onTransferFailure == other.onTransferFailure
                && forcePolicy == other.forcePolicy
                && requiredStateFilter.equals(other.requiredStateFilter);
    }

    private static Set<String> normalizeStateFilter(@Nullable String[] rawFilter) {
        if (rawFilter == null || rawFilter.length == 0) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String state : rawFilter) {
            String normalizedState = normalizeStateKey(state);
            if (normalizedState != null) {
                normalized.add(normalizedState);
            }
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }

    @Nullable
    private static String normalizeStateKey(@Nullable String state) {
        return state == null || state.isBlank() ? null : state.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesStateFilter(String state, String filter) {
        if (state.equals(filter) || state.startsWith(filter)) {
            return true;
        }
        for (String segment : state.split("[^a-z0-9]+")) {
            if (segment.equals(filter) || segment.startsWith(filter)) {
                return true;
            }
        }
        return false;
    }

    private enum AdmissionPhase {
        NONE,
        PREPARING,
        RESERVED,
        CLAIMING,
        APPLYING,
        CANCELING,
        COMMITTING,
        TERMINAL
    }

    enum ClaimCompletion {
        APPLYING,
        CANCEL_REQUIRED,
        RETRY_REQUIRED,
        REJECTED
    }
}
