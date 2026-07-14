package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionRelocationAdmissionService;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Handles delayed/off-screen NPC relocation requests for command items.
 */
public final class CommandNpcRelocationService {
    private static final long INITIAL_APPLY_DELAY_MS = 250L;
    private static final long CHUNK_REQUEST_COOLDOWN_MS = 1500L;
    private static final long RELOCATION_CONFIRMATION_DELAY_MS = 250L;
    private static final long RELOCATION_CONFIRMATION_TIMEOUT_MS = 5000L;
    private static final double DESTINATION_CONFIRM_TOLERANCE = 4.0;
    private final ConcurrentHashMap<UUID, PendingRelocation> pendingByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vector3d> lastKnownByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, World> knownWorldByNpc = new ConcurrentHashMap<>();
    private final CommandRelocationNpcLifecycle npcLifecycle;
    private final CommandRelocationAdmissionGate admissionGate = new CommandRelocationAdmissionGate();
    private final CommandRelocationPostMoveEffects postMoveEffects =
            new CommandRelocationPostMoveEffects();
    private final CommandRelocationWorldAccess worldAccess;
    private final CommandRelocationDropReporter dropReporter;
    private final CommandRelocationTimingPolicy timingPolicy = new CommandRelocationTimingPolicy();
    private final CommandRelocationRetryCoordinator retryCoordinator;
    private final CommandRelocationChunkLeaseService<PendingRelocation, WorldChunk> chunkLeases;
    private final CommandRelocationDiagnostics diagnostics;

    public CommandNpcRelocationService() {
        this(null);
    }

    public CommandNpcRelocationService(@Nullable HytaleLogger logger) {
        this.diagnostics = new CommandRelocationDiagnostics(logger);
        this.worldAccess = new CommandRelocationWorldAccess(knownWorldByNpc, this::logTravelDiagnostic);
        this.dropReporter = new CommandRelocationDropReporter(logger, this::logTravelDiagnostic);
        this.npcLifecycle = new CommandRelocationNpcLifecycle(
                lastKnownByNpc,
                knownWorldByNpc,
                pendingByNpc,
                dropReporter,
                (world, npcUuid) -> scheduleTryApply(world, npcUuid, INITIAL_APPLY_DELAY_MS),
                this::removePending,
                pending -> cancelAdmission(pending, false, null, null)
        );
        this.retryCoordinator = new CommandRelocationRetryCoordinator(this, timingPolicy);
        this.chunkLeases = new CommandRelocationChunkLeaseService<>(
                WorldChunk::addKeepLoaded,
                WorldChunk::removeKeepLoaded
        );
    }

    public void setRelocationDropListener(@Nullable CommandRelocationDropListener relocationDropListener) {
        dropReporter.setListener(relocationDropListener);
    }

    public void setRelocationAdmissionService(
            @Nullable CompanionRelocationAdmissionService relocationAdmissionService
    ) {
        admissionGate.setAuthority(relocationAdmissionService);
    }

    public void cancelPendingRelocation(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        PendingRelocation pending = pendingByNpc.get(npcUuid);
        if (pending == null) {
            return;
        }
        if (pending.admissionApplying() && pending.physicalMutationAttempted()) {
            commitUnconfirmedRelocationAsLost(
                    knownWorldByNpc.get(npcUuid), npcUuid, pending, System.currentTimeMillis()
            );
            return;
        }
        if (removePending(npcUuid, pending)) {
            pending.markCrossWorldTransferFinished();
            cancelAdmission(pending, false, null, null);
            logTravelDiagnostic(Level.INFO, "Cancelled pending relocation for npc=" + npcUuid);
        }
    }

    public LastKnownLocation getLastKnownLocation(@Nullable UUID npcUuid,
                                                  @Nullable Vector3d fallbackPosition,
                                                  @Nullable String fallbackWorldName) {
        if (npcUuid == null) {
            return new LastKnownLocation(
                    worldAccess.normalizeWorldName(fallbackWorldName), worldAccess.copyPosition(fallbackPosition)
            );
        }
        Vector3d position = lastKnownByNpc.get(npcUuid);
        World world = knownWorldByNpc.get(npcUuid);
        String worldName = world != null
                ? worldAccess.normalizeWorldName(world.getName())
                : worldAccess.normalizeWorldName(fallbackWorldName);
        return new LastKnownLocation(
                worldName,
                position != null ? new Vector3d(position) : worldAccess.copyPosition(fallbackPosition)
        );
    }

    @Nullable
    public PendingRecallSnapshot getPendingRecallSnapshot(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        PendingRelocation pending = pendingByNpc.get(npcUuid);
        if (pending == null) {
            return null;
        }
        long nowMs = System.currentTimeMillis();
        long maxWaitMs = Math.max(0L, timingPolicy.maxWaitMs());
        long lostAtMs = pending.queuedAtMs + maxWaitMs;
        return new PendingRecallSnapshot(
                pending.npcUuid,
                pending.queuedAtMs,
                Math.max(0L, lostAtMs - nowMs)
        );
    }

    public void queueRelocation(World world,
                                UUID npcUuid,
                                Vector3d destination,
                                @Nullable UUID ownerUuid,
                                boolean assignOwnerAsMasterTarget,
                                boolean clearLockedTarget,
                                @Nullable String state,
                                @Nullable String subState,
                                long delayMs,
                                @Nullable Vector3d sourceHintPosition,
                                @Nullable Vector3d alternateSourceHintPosition) {
        queueRelocation(
                world,
                npcUuid,
                destination,
                ownerUuid,
                assignOwnerAsMasterTarget,
                clearLockedTarget,
                state,
                subState,
                delayMs,
                sourceHintPosition,
                alternateSourceHintPosition,
                false,
                TwCompanionConfig.TransferFailurePolicy.QueueForRecall,
                null
        );
    }

    public void queueRelocation(World world,
                                UUID npcUuid,
                                Vector3d destination,
                                @Nullable UUID ownerUuid,
                                boolean assignOwnerAsMasterTarget,
                                boolean clearLockedTarget,
                                @Nullable String state,
                                @Nullable String subState,
                                long delayMs,
                                @Nullable Vector3d sourceHintPosition,
                                @Nullable Vector3d alternateSourceHintPosition,
                                boolean allowCrossWorldTransfer,
                                @Nullable TwCompanionConfig.TransferFailurePolicy onTransferFailure) {
        queueRelocation(
                world,
                npcUuid,
                destination,
                ownerUuid,
                assignOwnerAsMasterTarget,
                clearLockedTarget,
                state,
                subState,
                delayMs,
                sourceHintPosition,
                alternateSourceHintPosition,
                allowCrossWorldTransfer,
                onTransferFailure,
                null
        );
    }

    public void queueRelocation(World world,
                                UUID npcUuid,
                                Vector3d destination,
                                @Nullable UUID ownerUuid,
                                boolean assignOwnerAsMasterTarget,
                                boolean clearLockedTarget,
                                @Nullable String state,
                                @Nullable String subState,
                                long delayMs,
                                @Nullable Vector3d sourceHintPosition,
                                @Nullable Vector3d alternateSourceHintPosition,
                                boolean allowCrossWorldTransfer,
                                @Nullable TwCompanionConfig.TransferFailurePolicy onTransferFailure,
                                @Nullable String[] requiredStateFilter) {
        boolean debugLag = isLagDebugEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        try {
            if (world == null || npcUuid == null || destination == null) {
                return;
            }
            long executeAfterMs = System.currentTimeMillis() + Math.max(0L, delayMs);
            PendingRelocation pending = new PendingRelocation(
                    npcUuid,
                    new Vector3d(destination),
                    world.getName(),
                    sourceHintPosition != null ? new Vector3d(sourceHintPosition) : null,
                    alternateSourceHintPosition != null ? new Vector3d(alternateSourceHintPosition) : null,
                    ownerUuid,
                    assignOwnerAsMasterTarget,
                    clearLockedTarget,
                    state,
                    subState,
                    executeAfterMs,
                    System.currentTimeMillis(),
                    allowCrossWorldTransfer,
                    onTransferFailure,
                    requiredStateFilter,
                    CompanionRelocationAdmissionService.ForcePolicy.ENFORCE
            );
            if (sourceHintPosition != null) {
                // Do not overwrite an existing tracked position with a potentially stale metadata hint.
                lastKnownByNpc.putIfAbsent(npcUuid, new Vector3d(sourceHintPosition));
            } else if (alternateSourceHintPosition != null) {
                lastKnownByNpc.putIfAbsent(npcUuid, new Vector3d(alternateSourceHintPosition));
            }
            PendingRelocation current = pendingByNpc.get(npcUuid);
            if (current != null
                    && (current.hasSameCommandIntent(pending) || current.physicalMutationAttempted())) {
                requestChunksForPending(world, current);
                scheduleTryApply(world, npcUuid, 0L);
                return;
            }
            chunkLeases.open(pending);
            PendingRelocation replaced = pendingByNpc.put(npcUuid, pending);
            if (replaced != null) {
                chunkLeases.release(replaced);
                replaced.markCrossWorldTransferFinished();
                if (replaced.admissionApplying() && replaced.physicalMutationAttempted()) {
                    commitUnconfirmedRelocationAsLost(
                            knownWorldByNpc.get(npcUuid), npcUuid, replaced, System.currentTimeMillis()
                    );
                } else {
                    cancelAdmission(replaced, false, null, null);
                }
            }
            if (allowCrossWorldTransfer || (requiredStateFilter != null && requiredStateFilter.length > 0)) {
                logTravelDiagnostic(
                        Level.INFO,
                        "Queued relocation npc="
                                + npcUuid
                                + ", destinationWorld="
                                + world.getName()
                                + ", allowCrossWorldTransfer="
                                + allowCrossWorldTransfer
                                + ", onTransferFailure="
                                + pending.onTransferFailure
                                + ", requiredStateFilter="
                                + describeStateFilter(requiredStateFilter)
                );
            }
            requestChunksForPending(world, pending);
            scheduleTryApply(world, npcUuid, Math.max(0L, delayMs));
        } finally {
            if (debugLag) {
                logSlowOperation(startedNs, "relocation.queueRelocation npc=" + npcUuid);
            }
        }
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        npcLifecycle.onNpcAdded(reference, store);
    }

    public void onNpcRemoved(Ref<EntityStore> reference,
                             RemoveReason reason,
                             Store<EntityStore> store,
                             @Nullable UUID npcUuidHint) {
        npcLifecycle.onNpcRemoved(reference, reason, store, npcUuidHint);
    }

    /** Submits strict Lost recovery after a terminal world's live identity has been retired. */
    public void onWorldRemoved(@Nullable World world) {
        npcLifecycle.onWorldRemoved(world, System.currentTimeMillis());
    }

    public boolean isDeleteOnRemoveRecoveryPending(@Nullable UUID npcUuid) {
        return npcLifecycle.isDeleteOnRemoveRecoveryPending(npcUuid);
    }

    public boolean tryApply(World world, UUID npcUuid) {
        boolean debugLag = isLagDebugEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        try {
            if (world == null || npcUuid == null) {
                return false;
            }
            PendingRelocation pending = pendingByNpc.get(npcUuid);
            if (pending == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            if (now < pending.executeAfterMs) {
                scheduleTryApply(world, npcUuid, pending.executeAfterMs - now);
                return false;
            }
            Ref<EntityStore> ref = world.getEntityRef(npcUuid);
            if (ref == null || !ref.isValid()) {
                if (pending.isCrossWorldTransferInProgress()) {
                    scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
                    return false;
                }
                if (pending.allowCrossWorldTransfer && maybeStartCrossWorldTransfer(world, npcUuid, pending)) {
                    scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
                    return false;
                }
                retryCoordinator.afterLiveStateUnavailable(world, npcUuid, pending);
                return false;
            }
            Store<EntityStore> store = world.getEntityStore() == null ? null : world.getEntityStore().getStore();
            if (store == null) {
                retryCoordinator.afterLiveStateUnavailable(world, npcUuid, pending);
                return false;
            }
            NPCEntity npc = safeGetComponent(store, ref, NPCEntity.getComponentType());
            if (npc == null) {
                retryCoordinator.afterLiveStateUnavailable(world, npcUuid, pending);
                return false;
            }
            knownWorldByNpc.put(npcUuid, world);
            String currentState = resolveCurrentStateName(npc);
            if (!pending.physicalMutationAttempted() && !pending.isStateAllowed(currentState)) {
                logTravelDiagnostic(
                        Level.INFO,
                        "Skipped relocation due to state filter for npc="
                                + npcUuid
                                + ", destinationWorld="
                                + world.getName()
                                + ", currentState="
                                + currentState
                                + ", requiredStateFilter="
                                + pending.describeStateFilter()
                );
                pending.markCrossWorldTransferFinished();
                removePending(npcUuid, pending);
                cancelAdmission(pending, false, null, null);
                return false;
            }
            TransformComponent transform = safeGetComponent(store, ref, TransformComponent.getComponentType());
            if (transform == null) {
                retryCoordinator.afterLiveStateUnavailable(world, npcUuid, pending);
                return false;
            }
            if (!ensureAdmission(world, pending)) {
                return false;
            }
            if (!pending.relocationIssued) {
                if (pending.admissionReserved() && !hasExpectedLiveOwner(store, ref, pending)) {
                    cancelPendingAdmissionForDenial(
                            world, pending, "relocation-live-owner-changed"
                    );
                    return false;
                }
                if (!claimAdmissionImmediatelyBeforeMutation(world, pending)) {
                    return false;
                }
                pending.markRelocationIssued(now);
                try {
                    npc.moveTo(ref, pending.destination.x, pending.destination.y, pending.destination.z, store);
                } catch (RuntimeException | LinkageError exception) {
                    logTravelDiagnostic(
                            Level.WARNING,
                            "Relocation move requires confirmation after exception for npc="
                                    + npcUuid
                                    + ", reason="
                                    + exception.getClass().getSimpleName()
                    );
                }
                scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
                return false;
            }
            Vector3d currentPosition = new Vector3d(transform.getPosition());
            lastKnownByNpc.put(npcUuid, new Vector3d(currentPosition));
            if (!worldAccess.isAtDestination(
                    currentPosition, pending.destination, DESTINATION_CONFIRM_TOLERANCE
            )) {
                if (now - pending.relocationIssuedAtMs > RELOCATION_CONFIRMATION_TIMEOUT_MS) {
                    pending.resetRelocationIssue();
                    retryCoordinator.keepingAdmission(world, npcUuid, pending, true);
                } else {
                    scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
                }
                return false;
            }
            commitAdmission(world, npcUuid, pending);
            postMoveEffects.apply(
                    world, npc, ref, store, pending,
                    effect -> logTravelDiagnostic(
                            Level.WARNING,
                            "Relocation post-move effect failed for npc=" + npcUuid + ", effect=" + effect
                    )
            );
            return false;
        } finally {
            if (debugLag) {
                logSlowOperation(startedNs, "relocation.tryApply npc=" + npcUuid);
            }
        }
    }

    private boolean ensureAdmission(World world, PendingRelocation pending) {
        if (pending.ownerUuid == null) {
            denyAdmission(pending, "relocation-owner-missing");
            return false;
        }
        CompanionRelocationAdmissionService.Request request =
                new CompanionRelocationAdmissionService.Request(
                        pending.npcUuid,
                        pending.ownerUuid,
                        world.getName(),
                        worldAccess.toChunk(pending.destination.x),
                        worldAccess.toChunk(pending.destination.z),
                        pending.forcePolicy
                );
        return admissionGate.ensure(
                pending,
                request,
                leaseBoundDispatcher(world),
                () -> pendingByNpc.get(pending.npcUuid) == pending,
                () -> scheduleTryApply(world, pending.npcUuid, 0L),
                reason -> retryAdmission(world, pending, reason),
                reason -> denyAdmission(pending, reason)
        );
    }

    private boolean hasExpectedLiveOwner(Store<EntityStore> store,
                                         Ref<EntityStore> ref,
                                         PendingRelocation pending) {
        TameworkOwnerComponent owner = safeGetComponent(
                store, ref, TameworkOwnerComponent.getComponentType()
        );
        return owner != null && Objects.equals(owner.getOwnerId(), pending.ownerUuid);
    }

    private boolean claimAdmissionImmediatelyBeforeMutation(World world,
                                                            PendingRelocation pending) {
        return pending.admissionApplying() || admissionGate.claimForApply(
                pending,
                leaseBoundDispatcher(world),
                () -> pendingByNpc.get(pending.npcUuid) == pending,
                reason -> retryAdmission(world, pending, reason),
                reason -> denyAdmission(pending, reason)
        );
    }

    private void retryAdmission(World world, PendingRelocation pending, String reason) {
        if (world == null || pendingByNpc.get(pending.npcUuid) != pending) {
            return;
        }
        logTravelDiagnostic(Level.INFO,
                "Retrying relocation admission for npc=" + pending.npcUuid + ", reason=" + reason);
        retryCoordinator.keepingAdmission(world, pending.npcUuid, pending, false);
    }

    private void cancelPendingAdmissionForDenial(World world,
                                                  PendingRelocation pending,
                                                  String reason) {
        cancelAdmission(pending, false, world, null);
        denyAdmission(pending, reason);
    }

    void cancelAdmission(PendingRelocation pending,
                         boolean retry,
                         @Nullable World world,
                         @Nullable Runnable continuation) {
        admissionGate.cancel(
                pending,
                retry,
                leaseBoundDispatcher(world),
                () -> pendingByNpc.get(pending.npcUuid) == pending,
                continuation,
                reason -> denyAdmission(pending, reason)
        );
    }

    private void commitAdmission(World world, UUID npcUuid, PendingRelocation pending) {
        admissionGate.commit(pending, leaseBoundDispatcher(world), (decision, failure) -> {
            if (pendingByNpc.get(npcUuid) != pending) {
                return;
            }
            lastKnownByNpc.put(npcUuid, new Vector3d(pending.destination));
            removePending(npcUuid, pending);
            if (failure != null || decision == null
                    || decision.status() != CompanionRelocationAdmissionService.Status.COMMITTED) {
                logTravelDiagnostic(Level.WARNING,
                        "Relocation moved live NPC but population commit degraded for npc=" + npcUuid);
            }
        });
    }

    void commitUnconfirmedRelocationAsLost(
            @Nullable World world, UUID npcUuid, PendingRelocation pending, long droppedAtMs) {
        admissionGate.commit(pending, leaseBoundDispatcher(world), (decision, failure) -> {
            if (failure != null || decision == null
                    || decision.status() != CompanionRelocationAdmissionService.Status.COMMITTED) {
                logTravelDiagnostic(Level.WARNING,
                        "Unconfirmed relocation population commit degraded for npc=" + npcUuid);
            }
            dropPendingAsLost(npcUuid, pending, droppedAtMs);
        });
    }

    void cancelObservedSameWorldRelocation(
            World world, UUID npcUuid, PendingRelocation pending) {
        if (!removePending(npcUuid, pending)) {
            return;
        }
        pending.markPhysicalMutationCompensated();
        pending.markCrossWorldTransferFinished();
        cancelAdmission(pending, false, world, null);
        logTravelDiagnostic(
                Level.WARNING,
                "Relocation timed out with the live NPC confirmed outside the destination; "
                        + "retained source population state for npc=" + npcUuid
        );
    }

    private void denyAdmission(PendingRelocation pending, String reason) {
        removePending(pending.npcUuid, pending);
        logTravelDiagnostic(Level.WARNING,
                "Relocation admission denied for npc=" + pending.npcUuid + ", reason=" + reason);
    }

    private boolean maybeStartCrossWorldTransfer(World destinationWorld,
                                                 UUID npcUuid,
                                                 PendingRelocation pending) {
        if (destinationWorld == null || npcUuid == null || pending == null || !pending.allowCrossWorldTransfer) {
            return false;
        }
        World sourceWorld = knownWorldByNpc.get(npcUuid);
        if (sourceWorld == null || worldAccess.isSameWorld(sourceWorld, destinationWorld)) {
            if (sourceWorld == null && pending.markSourceWorldMissingLogged()) {
                logTravelDiagnostic(
                        Level.WARNING,
                        "Unable to start cross-world transfer for npc="
                                + npcUuid
                                + ": source world unknown while destinationWorld="
                                + destinationWorld.getName()
                );
            }
            return false;
        }
        if (!ensureAdmission(destinationWorld, pending)) {
            return true;
        }
        pending.resetSourceWorldMissingLogged();
        if (!pending.markCrossWorldTransferStarted()) {
            return true;
        }
        logTravelDiagnostic(
                Level.INFO,
                "Starting cross-world transfer npc="
                        + npcUuid
                        + ", sourceWorld="
                        + sourceWorld.getName()
                        + ", destinationWorld="
                        + destinationWorld.getName()
        );
        worldAccess.execute(
                sourceWorld,
                () -> transferPendingAcrossWorlds(sourceWorld, destinationWorld, npcUuid, pending),
                () -> {
                    pending.markCrossWorldTransferFinished();
                    pending.resetRelocationIssue();
                    knownWorldByNpc.remove(npcUuid, sourceWorld);
                    if (pending.admissionApplying() && pending.physicalMutationAttempted()) {
                        commitUnconfirmedRelocationAsLost(
                                destinationWorld, npcUuid, pending, System.currentTimeMillis()
                        );
                    } else {
                        retryPendingFromWorld(destinationWorld, npcUuid, pending);
                    }
                    logTravelDiagnostic(
                            Level.WARNING,
                            "Unable to transfer npc=" + npcUuid
                                    + ", reason=world-dispatch-or-task-failure"
                    );
                }
        );
        return true;
    }

    private void transferPendingAcrossWorlds(World sourceWorld,
                                             World destinationWorld,
                                             UUID npcUuid,
                                             PendingRelocation pending) {
        if (sourceWorld == null || destinationWorld == null || pending == null || npcUuid == null) {
            return;
        }
        if (pendingByNpc.get(npcUuid) != pending || !pending.isCrossWorldTransferInProgress()) {
            return;
        }
        if (worldAccess.isSameWorld(sourceWorld, destinationWorld)) {
            pending.markCrossWorldTransferFinished();
            return;
        }
        if (!destinationWorld.isAlive()) {
            pending.markCrossWorldTransferFinished();
            retryPendingFromWorld(destinationWorld, npcUuid, pending);
            return;
        }
        Store<EntityStore> sourceStore = sourceWorld.getEntityStore() != null ? sourceWorld.getEntityStore().getStore() : null;
        if (sourceStore == null) {
            logTravelDiagnostic(
                    Level.WARNING,
                    "Cross-world transfer failed before remove for npc="
                            + npcUuid
                            + ": source store missing for world="
                            + sourceWorld.getName()
            );
            pending.markCrossWorldTransferFinished();
            retryPendingFromWorld(destinationWorld, npcUuid, pending);
            return;
        }
        Ref<EntityStore> sourceRef = sourceWorld.getEntityRef(npcUuid);
        if (sourceRef == null || !sourceRef.isValid()) {
            logTravelDiagnostic(
                    Level.WARNING,
                    "Cross-world transfer failed before remove for npc="
                            + npcUuid
                            + ": source ref missing/invalid in world="
                            + sourceWorld.getName()
            );
            requestSourceChunksForPending(sourceWorld, pending);
            pending.markCrossWorldTransferFinished();
            retryPendingFromWorld(destinationWorld, npcUuid, pending);
            return;
        }
        NPCEntity sourceNpc = safeGetComponent(sourceStore, sourceRef, NPCEntity.getComponentType());
        if (sourceNpc == null) {
            logTravelDiagnostic(
                    Level.WARNING,
                    "Cross-world transfer failed before remove for npc="
                            + npcUuid
                            + ": source NPC component missing in world="
                            + sourceWorld.getName()
            );
            pending.markCrossWorldTransferFinished();
            retryPendingFromWorld(destinationWorld, npcUuid, pending);
            return;
        }
        if (worldAccess.isUnsafeTransferState(sourceStore, sourceRef, sourceNpc)) {
            String reason = sourceNpc.getRole() == null
                    ? "role-null"
                    : "mounted";
            logTravelDiagnostic(
                    Level.INFO,
                    "Cross-world transfer deferred for npc="
                            + npcUuid
                            + ", sourceWorld="
                            + sourceWorld.getName()
                            + ", destinationWorld="
                            + destinationWorld.getName()
                            + ", reason="
                            + reason
            );
            pending.markCrossWorldTransferFinished();
            retryPendingFromWorld(destinationWorld, npcUuid, pending);
            return;
        }
        String sourceState = resolveCurrentStateName(sourceNpc);
        if (!pending.isStateAllowed(sourceState)) {
            logTravelDiagnostic(
                    Level.INFO,
                    "Cross-world transfer cancelled by state filter for npc="
                            + npcUuid
                            + ", sourceWorld="
                            + sourceWorld.getName()
                            + ", state="
                            + sourceState
                            + ", requiredStateFilter="
                            + pending.describeStateFilter()
            );
            cancelPendingForStateFilter(npcUuid, pending);
            return;
        }
        if (!hasExpectedLiveOwner(sourceStore, sourceRef, pending)) {
            pending.markCrossWorldTransferFinished();
            cancelPendingAdmissionForDenial(
                    sourceWorld, pending, "relocation-live-owner-changed"
            );
            return;
        }
        Holder<EntityStore> drainedHolder;
        if (!claimAdmissionImmediatelyBeforeMutation(sourceWorld, pending)) {
            pending.markCrossWorldTransferFinished();
            return;
        }
        pending.markPhysicalMutationAttempted();
        try {
            drainedHolder = sourceStore.removeEntity(sourceRef, RemoveReason.UNLOAD);
        } catch (RuntimeException | LinkageError exception) {
            handleSourceRemoveFailure(
                    sourceWorld, destinationWorld, npcUuid, pending,
                    "exception=" + exception.getClass().getSimpleName()
            );
            return;
        }
        if (drainedHolder == null) {
            handleSourceRemoveFailure(
                    sourceWorld, destinationWorld, npcUuid, pending, "empty-holder"
            );
            return;
        }
        worldAccess.execute(destinationWorld, () -> {
            if (pendingByNpc.get(npcUuid) != pending) {
                logTravelDiagnostic(
                        Level.INFO,
                        "Cross-world transfer aborted because pending relocation was replaced/cleared for npc=" + npcUuid
                );
                worldAccess.execute(
                        sourceWorld,
                        () -> worldAccess.restoreSourceEntity(
                                sourceWorld, sourceStore, drainedHolder, npcUuid
                        ),
                        () -> terminalizeDrainedTransferAsLost(
                                npcUuid, pending,
                                "relocation-replaced-source-restore-dispatch-rejected"
                        )
                );
                return;
            }
            Store<EntityStore> destinationStore =
                    destinationWorld.getEntityStore() != null ? destinationWorld.getEntityStore().getStore() : null;
            if (destinationStore == null) {
                logTravelDiagnostic(
                        Level.WARNING,
                        "Cross-world transfer failed before add for npc="
                                + npcUuid
                                + ": destination store missing for world="
                                + destinationWorld.getName()
                );
                restoreSourceEntityAndApplyFailure(
                        sourceWorld,
                        sourceStore,
                        drainedHolder,
                        destinationWorld,
                        npcUuid,
                        pending
                );
                return;
            }
            Ref<EntityStore> destinationRef = null;
            try {
                destinationRef = destinationStore.addEntity(drainedHolder, AddReason.SPAWN);
            } catch (Exception ex) {
                if (worldAccess.isEntityPresent(destinationWorld, npcUuid)) {
                    pending.markCrossWorldDestinationInstalled();
                    pending.markCrossWorldTransferFinished();
                    knownWorldByNpc.put(npcUuid, destinationWorld);
                    logTravelDiagnostic(
                            Level.INFO,
                            "Cross-world transfer accepted destination entity after add exception for npc="
                                    + npcUuid
                                    + ", sourceWorld="
                                    + sourceWorld.getName()
                                    + ", destinationWorld="
                                    + destinationWorld.getName()
                    );
                    scheduleTryApply(destinationWorld, npcUuid, INITIAL_APPLY_DELAY_MS);
                    return;
                }
                logTravelDiagnostic(
                        Level.WARNING,
                        "Cross-world transfer failed while adding destination entity for npc="
                                + npcUuid
                                + ", destinationWorld="
                                + destinationWorld.getName()
                                + ", reason="
                                + ex.getClass().getSimpleName()
                                + ": "
                                + ex.getMessage()
                );
                restoreSourceEntityAndApplyFailure(
                        sourceWorld,
                        sourceStore,
                        drainedHolder,
                        destinationWorld,
                        npcUuid,
                        pending
                );
                return;
            }
            if (destinationRef == null || !destinationRef.isValid()
                    || !worldAccess.isEntityPresent(destinationWorld, npcUuid)) {
                if (worldAccess.isEntityPresent(destinationWorld, npcUuid)) {
                    pending.markCrossWorldDestinationInstalled();
                    pending.markCrossWorldTransferFinished();
                    knownWorldByNpc.put(npcUuid, destinationWorld);
                    logTravelDiagnostic(
                            Level.INFO,
                            "Cross-world transfer accepted destination entity with non-valid add ref for npc="
                                    + npcUuid
                                    + ", sourceWorld="
                                    + sourceWorld.getName()
                                    + ", destinationWorld="
                                    + destinationWorld.getName()
                    );
                    scheduleTryApply(destinationWorld, npcUuid, INITIAL_APPLY_DELAY_MS);
                    return;
                }
                logTravelDiagnostic(
                        Level.WARNING,
                        "Cross-world transfer failed while adding destination entity for npc="
                                + npcUuid
                                + ", destinationWorld="
                                + destinationWorld.getName()
                );
                restoreSourceEntityAndApplyFailure(
                        sourceWorld,
                        sourceStore,
                        drainedHolder,
                        destinationWorld,
                        npcUuid,
                        pending
                );
                return;
            }
            pending.markCrossWorldDestinationInstalled();
            pending.markCrossWorldTransferFinished();
            knownWorldByNpc.put(npcUuid, destinationWorld);
            logTravelDiagnostic(
                    Level.INFO,
                    "Cross-world transfer succeeded for npc="
                            + npcUuid
                            + ", sourceWorld="
                            + sourceWorld.getName()
                            + ", destinationWorld="
                            + destinationWorld.getName()
            );
            scheduleTryApply(destinationWorld, npcUuid, INITIAL_APPLY_DELAY_MS);
        }, () -> terminalizeDrainedTransferAsLost(
                npcUuid, pending, "relocation-destination-dispatch-rejected"
        ));
    }

    private void restoreSourceEntityAndApplyFailure(World sourceWorld,
                                                    @Nullable Store<EntityStore> sourceStore,
                                                    @Nullable Holder<EntityStore> drainedHolder,
                                                    World destinationWorld,
                                                    UUID npcUuid,
                                                    PendingRelocation pending) {
        if (destinationWorld == null || npcUuid == null || pending == null) {
            return;
        }
        if (sourceWorld == null || sourceStore == null || drainedHolder == null) {
            commitUnconfirmedRelocationAsLost(
                    destinationWorld, npcUuid, pending, System.currentTimeMillis()
            );
            return;
        }
        worldAccess.execute(sourceWorld, () -> {
            if (!worldAccess.restoreSourceEntity(sourceWorld, sourceStore, drainedHolder, npcUuid)) {
                commitUnconfirmedRelocationAsLost(
                        destinationWorld, npcUuid, pending, System.currentTimeMillis()
                );
                return;
            }
            pending.markPhysicalMutationCompensated();
            worldAccess.execute(
                    destinationWorld,
                    () -> applyTransferFailurePolicy(destinationWorld, npcUuid, pending),
                    () -> terminalizeRelocation(pending, "relocation-failure-dispatch-rejected")
            );
        }, () -> terminalizeDrainedTransferAsLost(
                npcUuid, pending, "relocation-source-restore-dispatch-rejected"
        ));
    }

    /**
     * Closes an already-drained transfer without touching either world's ECS. This method can run
     * from the lease watchdog, so the population admission is conservatively committed as lost.
     */
    private void terminalizeDrainedTransferAsLost(
            UUID npcUuid,
            PendingRelocation pending,
            String reason
    ) {
        pending.markCrossWorldTransferFinished();
        logTravelDiagnostic(
                Level.WARNING,
                "Cross-world transfer became unobservable after source removal for npc="
                        + npcUuid + ", reason=" + reason
        );
        commitUnconfirmedRelocationAsLost(
                null, npcUuid, pending, System.currentTimeMillis()
        );
    }

    private void handleSourceRemoveFailure(World sourceWorld,
                                           World destinationWorld,
                                           UUID npcUuid,
                                           PendingRelocation pending,
                                           String detail) {
        logTravelDiagnostic(
                Level.WARNING,
                "Cross-world source removal requires confirmation for npc=" + npcUuid
                        + ", sourceWorld=" + sourceWorld.getName() + ", " + detail
        );
        if (worldAccess.isEntityPresent(sourceWorld, npcUuid)) {
            pending.markPhysicalMutationCompensated();
            pending.markCrossWorldTransferFinished();
            retryPendingFromWorld(destinationWorld, npcUuid, pending);
            return;
        }
        if (worldAccess.isEntityPresent(destinationWorld, npcUuid)) {
            pending.markCrossWorldTransferFinished();
            knownWorldByNpc.put(npcUuid, destinationWorld);
            scheduleTryApply(destinationWorld, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
            return;
        }
        pending.markCrossWorldTransferFinished();
        commitUnconfirmedRelocationAsLost(
                destinationWorld, npcUuid, pending, System.currentTimeMillis()
        );
    }

    private void retryPendingFromWorld(World world, UUID npcUuid, PendingRelocation pending) {
        worldAccess.execute(world, () -> {
            if (pendingByNpc.get(npcUuid) != pending) {
                return;
            }
            pending.resetRelocationIssue();
            retryCoordinator.retry(world, npcUuid, pending);
        }, () -> terminalizeRelocation(pending, "relocation-world-dispatch-rejected"));
    }

    private void terminalizeRelocation(PendingRelocation pending, String reason) {
        pending.markCrossWorldTransferFinished();
        if (pending.admissionApplying() && pending.physicalMutationAttempted()) {
            commitUnconfirmedRelocationAsLost(
                    knownWorldByNpc.get(pending.npcUuid), pending.npcUuid, pending, System.currentTimeMillis()
            );
            return;
        }
        removePending(pending.npcUuid, pending);
        cancelAdmission(pending, false, null, null);
        logTravelDiagnostic(
                Level.WARNING,
                "Relocation terminalized for npc=" + pending.npcUuid + ", reason=" + reason
        );
    }

    private void applyTransferFailurePolicy(World world, UUID npcUuid, PendingRelocation pending) {
        if (world == null || npcUuid == null || pending == null) {
            return;
        }
        pending.markCrossWorldTransferFinished();
        TwCompanionConfig.TransferFailurePolicy policy = pending.onTransferFailure;
        logTravelDiagnostic(
                Level.WARNING,
                "Applying transfer failure policy for npc="
                        + npcUuid
                        + ", destinationWorld="
                        + world.getName()
                        + ", policy="
                        + policy
        );
        if (policy == TwCompanionConfig.TransferFailurePolicy.Ignore) {
            removePending(npcUuid, pending);
            cancelAdmission(pending, false, null, null);
            return;
        }
        if (policy == TwCompanionConfig.TransferFailurePolicy.MarkLost) {
            dropPendingAsLost(npcUuid, pending, System.currentTimeMillis());
            return;
        }
        pending.resetRelocationIssue();
        retryCoordinator.retry(world, npcUuid, pending);
    }

    private void cancelPendingForStateFilter(UUID npcUuid, PendingRelocation pending) {
        if (npcUuid == null || pending == null) {
            return;
        }
        pending.markCrossWorldTransferFinished();
        removePending(npcUuid, pending);
        cancelAdmission(pending, false, null, null);
        logTravelDiagnostic(
                Level.INFO,
                "Cancelled relocation due to state filter for npc="
                        + npcUuid
                        + ", requiredStateFilter="
                        + pending.describeStateFilter()
        );
    }

    void dropPendingAsLost(UUID npcUuid, PendingRelocation pending, long droppedAtMs) {
        if (npcUuid == null || pending == null) {
            return;
        }
        if (pending.admissionApplying() && pending.physicalMutationAttempted()) {
            commitUnconfirmedRelocationAsLost(
                    knownWorldByNpc.get(npcUuid), npcUuid, pending, droppedAtMs
            );
            return;
        }
        removePending(npcUuid, pending);
        cancelAdmission(pending, false, null, null);
        dropReporter.report(pending, droppedAtMs);
    }

    void requestChunksForPending(World world, PendingRelocation pending) {
        if (world == null || pending == null) {
            return;
        }
        requestChunkLoad(world, pending, pending.destination);
        requestSourceHints(world, pending);
    }

    private void requestChunkLoad(World world, PendingRelocation pending, Vector3d position) {
        if (world == null || pending == null || position == null) {
            return;
        }
        int chunkX = worldAccess.toChunk(position.x);
        int chunkZ = worldAccess.toChunk(position.z);
        long chunkKey = worldAccess.toChunkKey(chunkX, chunkZ);
        long now = System.currentTimeMillis();
        if (!pending.shouldRequestChunk(chunkKey, now, CHUNK_REQUEST_COOLDOWN_MS)) {
            return;
        }
        CompletableFuture<WorldChunk> request = world.getChunkAsync(chunkX, chunkZ);
        request.whenComplete((chunk, throwable) -> {
            if (pendingByNpc.get(pending.npcUuid) != pending) {
                return;
            }
            if (throwable == null && chunk != null) {
                if (!chunkLeases.retain(pending, chunk)) {
                    diagnostics.chunkLeaseNotRetained(pending.npcUuid, chunkX, chunkZ);
                }
                scheduleTryApply(world, pending.npcUuid, INITIAL_APPLY_DELAY_MS);
            } else {
                diagnostics.chunkRequestFailed(pending.npcUuid, chunkX, chunkZ, throwable);
            }
        });
    }

    private boolean removePending(UUID npcUuid, PendingRelocation pending) {
        boolean removed = pendingByNpc.remove(npcUuid, pending);
        if (removed) {
            chunkLeases.release(pending);
        }
        return removed;
    }

    /** Releases engine chunk leases when the plugin is disabled between world sessions. */
    public void close() {
        chunkLeases.close();
    }

    void scheduleTryApply(World world, UUID npcUuid, long delayMs) {
        if (world == null || npcUuid == null) {
            return;
        }
        PendingRelocation pending = pendingByNpc.get(npcUuid);
        if (pending == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        long dueAtMs = System.currentTimeMillis() + safeDelayMs;
        if (!pending.reserveScheduledApply(dueAtMs)) {
            return;
        }
        Runnable dispatch = () -> worldAccess.execute(
                world,
                () -> tryApplyIfScheduled(world, npcUuid, pending, dueAtMs),
                () -> {
                    if (pending.admissionApplying() && pending.physicalMutationAttempted()) {
                        commitUnconfirmedRelocationAsLost(
                                world, npcUuid, pending, System.currentTimeMillis()
                        );
                    } else {
                        terminalizeRelocation(pending, "relocation-confirmation-dispatch-rejected");
                    }
                }
        );
        try {
            CompletableFuture.runAsync(
                    dispatch,
                    CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS)
            );
        } catch (RuntimeException | LinkageError exception) {
            dispatch.run();
        }
    }

    private static CommandRelocationAdmissionGate.Dispatcher leaseBoundDispatcher(
            @Nullable World world
    ) {
        if (world == null) {
            return (task, rejected) -> task.run();
        }
        return (task, rejected) -> LeaseBoundWorldDispatcher.execute(
                world, task, rejected
        );
    }

    private void tryApplyIfScheduled(World world, UUID npcUuid, PendingRelocation pending, long dueAtMs) {
        if (world == null || npcUuid == null || pending == null) {
            return;
        }
        if (pendingByNpc.get(npcUuid) != pending) {
            return;
        }
        if (!pending.consumeScheduledApply(dueAtMs)) {
            return;
        }
        tryApply(world, npcUuid);
    }

    @Nullable
    private String resolveCurrentStateName(@Nullable NPCEntity npc) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        String state = npc.getRole().getStateSupport().getStateName();
        return state != null && !state.isBlank() ? state : null;
    }

    private static String describeStateFilter(@Nullable String[] stateFilter) {
        return stateFilter == null || stateFilter.length == 0
                ? "[]" : java.util.Arrays.toString(stateFilter);
    }

    private boolean isLagDebugEnabled() {
        return diagnostics.isLagDebugEnabled();
    }

    private void logTravelDiagnostic(Level level, String message) {
        diagnostics.log(level, message);
    }

    private void logSlowOperation(long startedNs, String operation) {
        diagnostics.logSlowOperation(startedNs, operation);
    }

    void logRetryProgress(PendingRelocation pending, long nowMs) {
        diagnostics.logRetryProgress(pending, nowMs);
    }

    @Nullable
    private <T extends Component<EntityStore>> T safeGetComponent(@Nullable Store<EntityStore> store,
                                                                  @Nullable Ref<EntityStore> reference,
                                                                  @Nullable ComponentType<EntityStore, T> componentType) {
        if (store == null || reference == null || !reference.isValid() || componentType == null) {
            return null;
        }
        try {
            return store.getComponent(reference, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            return null;
        }
    }

    public record LastKnownLocation(@Nullable String worldName, @Nullable Vector3d position) {
    }

    public record PendingRecallSnapshot(UUID npcUuid, long queuedAtMs, long remainingUntilLostMs) {
    }

    private void requestSourceChunksForPending(World sourceWorld, PendingRelocation pending) {
        if (sourceWorld == null || pending == null) {
            return;
        }
        requestSourceHints(sourceWorld, pending);
    }

    private void requestSourceHints(World sourceWorld, PendingRelocation pending) {
        Vector3d hintedSource = pending.sourceHintPosition;
        Vector3d alternateSource = pending.alternateSourceHintPosition;
        Vector3d cachedSource = lastKnownByNpc.get(pending.npcUuid);
        if (hintedSource != null) {
            requestChunkLoad(sourceWorld, pending, hintedSource);
        }
        if (alternateSource != null
                && (hintedSource == null || !worldAccess.isNear(alternateSource, hintedSource, 0.5))) {
            requestChunkLoad(sourceWorld, pending, alternateSource);
        }
        if (cachedSource != null
                && (hintedSource == null || !worldAccess.isNear(cachedSource, hintedSource, 0.5))
                && (alternateSource == null || !worldAccess.isNear(cachedSource, alternateSource, 0.5))) {
            requestChunkLoad(sourceWorld, pending, cachedSource);
        }
    }
}
