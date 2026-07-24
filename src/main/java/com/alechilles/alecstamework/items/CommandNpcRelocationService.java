package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Handles delayed/off-screen NPC relocation requests for command items.
 */
public final class CommandNpcRelocationService {
    private static final long INITIAL_APPLY_DELAY_MS = 250L;
    private static final long RELOCATION_CONFIRMATION_DELAY_MS = 250L;
    private static final long RELOCATION_CONFIRMATION_TIMEOUT_MS = 5000L;
    private static final double DESTINATION_CONFIRM_TOLERANCE = 4.0;
    private final ConcurrentHashMap<UUID, PendingRelocation> pendingByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vector3d> lastKnownByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, World> knownWorldByNpc = new ConcurrentHashMap<>();
    private final CommandRelocationNpcLifecycle npcLifecycle;
    private final CommandRelocationPostMoveEffects postMoveEffects =
            new CommandRelocationPostMoveEffects();
    private final CommandRelocationWorldAccess worldAccess;
    private final CommandRelocationApplyScheduler applyScheduler;
    private final CommandRelocationDropReporter dropReporter;
    private final CommandRelocationTimingPolicy timingPolicy = new CommandRelocationTimingPolicy();
    private final CommandRelocationRetryCoordinator retryCoordinator;
    private final CommandRelocationChunkRequestService chunkRequests;
    private final CommandRelocationTransferHolderService transferHolders =
            new CommandRelocationTransferHolderService();
    private final CommandRelocationDiagnostics diagnostics;

    public CommandNpcRelocationService() {
        this(null);
    }

    public CommandNpcRelocationService(@Nullable HytaleLogger logger) {
        this.diagnostics = new CommandRelocationDiagnostics(logger);
        this.worldAccess = new CommandRelocationWorldAccess(knownWorldByNpc, this::logTravelDiagnostic);
        this.applyScheduler = new CommandRelocationApplyScheduler(
                pendingByNpc,
                worldAccess,
                this::tryApply,
                this::handleApplyDispatchRejected
        );
        this.dropReporter = new CommandRelocationDropReporter(logger, this::logTravelDiagnostic);
        this.npcLifecycle = new CommandRelocationNpcLifecycle(
                lastKnownByNpc,
                knownWorldByNpc,
                pendingByNpc,
                (world, npcUuid) -> scheduleTryApply(
                        world, npcUuid, INITIAL_APPLY_DELAY_MS
                )
        );
        this.retryCoordinator = new CommandRelocationRetryCoordinator(this, timingPolicy);
        this.chunkRequests = new CommandRelocationChunkRequestService(
                pendingByNpc,
                lastKnownByNpc,
                worldAccess,
                diagnostics,
                this::scheduleTryApply
        );
    }

    /**
     * Seeds process-local source routing from the released linked-record world hint.
     * No world work is blocked here; the normal chunk lease and retry path verifies the NPC.
     */
    public void rememberSourceWorld(@Nullable UUID npcUuid, @Nullable String worldName) {
        if (npcUuid == null || knownWorldByNpc.containsKey(npcUuid)) {
            return;
        }
        World sourceWorld = worldAccess.resolveLoadedWorld(worldName);
        if (sourceWorld != null) {
            knownWorldByNpc.putIfAbsent(npcUuid, sourceWorld);
        }
    }

    public void cancelPendingRelocation(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        PendingRelocation pending = pendingByNpc.get(npcUuid);
        if (pending == null) {
            return;
        }
        if (removePending(npcUuid, pending)) {
            pending.markCrossWorldTransferFinished();
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
        long dropAtMs = pending.queuedAtMs + maxWaitMs;
        return new PendingRecallSnapshot(
                pending.npcUuid,
                pending.queuedAtMs,
                Math.max(0L, dropAtMs - nowMs)
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
                    requiredStateFilter
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
            chunkRequests.open(pending);
            PendingRelocation replaced = pendingByNpc.put(npcUuid, pending);
            if (replaced != null) {
                chunkRequests.release(replaced);
                replaced.markCrossWorldTransferFinished();
                if (replaced.physicalMutationAttempted()) {
                    dropUnconfirmedRelocation(
                            knownWorldByNpc.get(npcUuid), npcUuid, replaced, System.currentTimeMillis()
                    );
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
            NPCEntity npc = worldAccess.safeGetComponent(store, ref, NPCEntity.getComponentType());
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
                return false;
            }
            TransformComponent transform = worldAccess.safeGetComponent(
                    store, ref, TransformComponent.getComponentType());
            if (transform == null) {
                retryCoordinator.afterLiveStateUnavailable(world, npcUuid, pending);
                return false;
            }
            if (!pending.relocationIssued) {
                if (!hasExpectedLiveOwner(store, ref, pending)) {
                    rejectRelocation(pending, "relocation-live-owner-changed");
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
                    retryCoordinator.continueRetry(world, npcUuid, pending, true);
                } else {
                    scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
                }
                return false;
            }
            finishRelocation(npcUuid, pending);
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

    private boolean hasExpectedLiveOwner(Store<EntityStore> store,
                                         Ref<EntityStore> ref,
                                         PendingRelocation pending) {
        TameworkOwnerComponent owner = worldAccess.safeGetComponent(
                store, ref, TameworkOwnerComponent.getComponentType()
        );
        return owner != null && Objects.equals(owner.getOwnerId(), pending.ownerUuid);
    }

    private void finishRelocation(UUID npcUuid, PendingRelocation pending) {
        if (pendingByNpc.get(npcUuid) != pending) {
            return;
        }
        lastKnownByNpc.put(npcUuid, new Vector3d(pending.destination));
        removePending(npcUuid, pending);
    }

    void dropUnconfirmedRelocation(
            @Nullable World world, UUID npcUuid, PendingRelocation pending, long droppedAtMs) {
        dropRetryExhausted(npcUuid, pending, droppedAtMs);
    }

    void commitUnconfirmedRelocationAsUnloaded(
            World world, UUID npcUuid, PendingRelocation pending) {
        logTravelDiagnostic(
                Level.INFO,
                "Same-world relocation became unobservable after its physical move; "
                        + "retaining unloaded destination state for npc=" + npcUuid
        );
        finishRelocation(npcUuid, pending);
    }

    void cancelObservedSameWorldRelocation(
            World world, UUID npcUuid, PendingRelocation pending) {
        if (!removePending(npcUuid, pending)) {
            return;
        }
        pending.markPhysicalMutationCompensated();
        pending.markCrossWorldTransferFinished();
        logTravelDiagnostic(
                Level.WARNING,
                "Relocation timed out with the live NPC confirmed outside the destination; "
                        + "retained the observed live source state for npc=" + npcUuid
        );
    }

    private void rejectRelocation(PendingRelocation pending, String reason) {
        removePending(pending.npcUuid, pending);
        logTravelDiagnostic(Level.WARNING,
                "Relocation rejected for npc=" + pending.npcUuid + ", reason=" + reason);
    }

    private boolean maybeStartCrossWorldTransfer(World destinationWorld,
                                                 UUID npcUuid,
                                                 PendingRelocation pending) {
        if (destinationWorld == null || npcUuid == null || pending == null || !pending.allowCrossWorldTransfer) {
            return false;
        }
        if (pending.crossWorldDestinationInstalled()
                || !chunkRequests.isDestinationReady(destinationWorld, pending)) {
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
                    if (pending.physicalMutationAttempted()) {
                        dropUnconfirmedRelocation(
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
            chunkRequests.requestSource(sourceWorld, destinationWorld, pending);
            pending.markCrossWorldTransferFinished();
            retryPendingFromWorld(destinationWorld, npcUuid, pending);
            return;
        }
        NPCEntity sourceNpc = worldAccess.safeGetComponent(
                sourceStore, sourceRef, NPCEntity.getComponentType());
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
            rejectRelocation(pending, "relocation-live-owner-changed");
            return;
        }
        Holder<EntityStore> drainedHolder;
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
        CommandRelocationTransferHolderService.SourceTransform sourceTransform =
                transferHolders.prepareForDestination(drainedHolder, pending.destination);
        if (sourceTransform == null) {
            logTravelDiagnostic(
                    Level.WARNING,
                    "Cross-world transfer failed after remove for npc="
                            + npcUuid
                            + ": detached transform missing"
            );
            restoreSourceEntityAndApplyFailure(
                    sourceWorld, sourceStore, drainedHolder, null,
                    destinationWorld, npcUuid, pending
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
                        () -> restoreSourceEntity(
                                sourceWorld, sourceStore, drainedHolder, sourceTransform, npcUuid),
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
                        sourceTransform,
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
                        sourceTransform,
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
                        sourceTransform,
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
                                                    @Nullable CommandRelocationTransferHolderService.SourceTransform sourceTransform,
                                                    World destinationWorld,
                                                    UUID npcUuid,
                                                    PendingRelocation pending) {
        if (destinationWorld == null || npcUuid == null || pending == null) {
            return;
        }
        if (sourceWorld == null || sourceStore == null || drainedHolder == null) {
            dropUnconfirmedRelocation(
                    destinationWorld, npcUuid, pending, System.currentTimeMillis()
            );
            return;
        }
        worldAccess.execute(sourceWorld, () -> {
            if (!restoreSourceEntity(
                    sourceWorld, sourceStore, drainedHolder, sourceTransform, npcUuid)) {
                dropUnconfirmedRelocation(
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

    private boolean restoreSourceEntity(
            World sourceWorld,
            Store<EntityStore> sourceStore,
            Holder<EntityStore> drainedHolder,
            @Nullable CommandRelocationTransferHolderService.SourceTransform sourceTransform,
            UUID npcUuid
    ) {
        if (sourceTransform != null && !transferHolders.restoreSource(drainedHolder, sourceTransform)) {
            return false;
        }
        return worldAccess.restoreSourceEntity(sourceWorld, sourceStore, drainedHolder, npcUuid);
    }

    /**
     * Closes an already-drained transfer without touching either world's ECS. This method can run
     * from the lease watchdog, so it only closes the request and reports the loss.
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
        dropUnconfirmedRelocation(
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
        dropUnconfirmedRelocation(
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
        if (pending.physicalMutationAttempted()) {
            dropUnconfirmedRelocation(
                    knownWorldByNpc.get(pending.npcUuid), pending.npcUuid, pending, System.currentTimeMillis()
            );
            return;
        }
        removePending(pending.npcUuid, pending);
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
            return;
        }
        if (policy == TwCompanionConfig.TransferFailurePolicy.MarkLost) {
            dropRetryExhausted(
                    npcUuid, pending, System.currentTimeMillis()
            );
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
        logTravelDiagnostic(
                Level.INFO,
                "Cancelled relocation due to state filter for npc="
                        + npcUuid
                        + ", requiredStateFilter="
                        + pending.describeStateFilter()
        );
    }

    void dropRetryExhausted(
            UUID npcUuid,
            PendingRelocation pending,
            long droppedAtMs
    ) {
        if (npcUuid == null || pending == null) {
            return;
        }
        removePending(npcUuid, pending);
        dropReporter.report(pending, droppedAtMs);
    }

    void requestChunksForPending(World world, PendingRelocation pending) {
        chunkRequests.requestDestinationAndSource(world, pending);
    }

    private boolean removePending(UUID npcUuid, PendingRelocation pending) {
        boolean removed = pendingByNpc.remove(npcUuid, pending);
        if (removed) {
            chunkRequests.release(pending);
        }
        return removed;
    }

    /** Releases engine chunk leases when the plugin is disabled between world sessions. */
    public void close() {
        chunkRequests.close();
    }

    void scheduleTryApply(World world, UUID npcUuid, long delayMs) {
        applyScheduler.schedule(world, npcUuid, delayMs);
    }

    private void handleApplyDispatchRejected(
            World world,
            UUID npcUuid,
            PendingRelocation pending
    ) {
        if (pending.physicalMutationAttempted()) {
            dropUnconfirmedRelocation(
                    world, npcUuid, pending, System.currentTimeMillis()
            );
            return;
        }
        terminalizeRelocation(pending, "relocation-confirmation-dispatch-rejected");
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

    public record LastKnownLocation(@Nullable String worldName, @Nullable Vector3d position) {
    }

    public record PendingRecallSnapshot(
            UUID npcUuid,
            long queuedAtMs,
            long remainingUntilDropMs
    ) {
    }

}
