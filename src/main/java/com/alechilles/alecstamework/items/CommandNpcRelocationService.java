package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
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
    private final CommandRelocationLocationTracker locationTracker;
    private final CommandRelocationApplyScheduler applyScheduler;
    private final CommandRelocationTimingPolicy timingPolicy = new CommandRelocationTimingPolicy();
    private final CommandRelocationRetryCoordinator retryCoordinator;
    private final CommandRelocationChunkRequestService chunkRequests;
    private final CommandRelocationQueueCoordinator queueCoordinator;
    private final CommandRelocationDiagnostics diagnostics;
    private final CommandRelocationTerminalService terminalService;

    public CommandNpcRelocationService() {
        this(null, ImportedRecallRecoverySink.NOOP);
    }

    public CommandNpcRelocationService(@Nullable HytaleLogger logger) {
        this(logger, ImportedRecallRecoverySink.NOOP);
    }

    public CommandNpcRelocationService(
            @Nullable HytaleLogger logger,
            ImportedRecallRecoverySink importedRecallRecovery
    ) {
        this.diagnostics = new CommandRelocationDiagnostics(logger);
        this.worldAccess = new CommandRelocationWorldAccess();
        this.locationTracker = new CommandRelocationLocationTracker(
                lastKnownByNpc,
                knownWorldByNpc,
                worldAccess
        );
        this.applyScheduler = new CommandRelocationApplyScheduler(
                pendingByNpc,
                worldAccess,
                this::tryApply,
                this::handleApplyDispatchRejected
        );
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
        this.queueCoordinator = new CommandRelocationQueueCoordinator(
                pendingByNpc,
                lastKnownByNpc,
                chunkRequests,
                applyScheduler,
                this::dropUnconfirmedRelocation,
                this::logTravelDiagnostic
        );
        CommandRelocationRecoveryRetryService recoveryRetries =
                new CommandRelocationRecoveryRetryService(
                        pendingByNpc,
                        worldAccess,
                        queueCoordinator,
                        this::logTravelDiagnostic
                );
        this.terminalService = new CommandRelocationTerminalService(
                logger,
                importedRecallRecovery,
                pendingByNpc,
                this::removePending,
                this::logTravelDiagnostic,
                recoveryRetries::retry
        );
    }

    public void rememberSourceWorld(@Nullable UUID npcUuid, @Nullable String worldName) {
        locationTracker.rememberSourceWorld(npcUuid, worldName);
    }

    public void cancelPendingRelocation(@Nullable UUID npcUuid) {
        terminalService.cancel(npcUuid);
    }

    public LastKnownLocation getLastKnownLocation(@Nullable UUID npcUuid,
                                                  @Nullable Vector3d fallbackPosition,
                                                  @Nullable String fallbackWorldName) {
        CommandRelocationLocationTracker.Location location =
                locationTracker.resolve(
                        npcUuid,
                        fallbackPosition,
                        fallbackWorldName
                );
        return new LastKnownLocation(
                location.worldName(),
                location.position()
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
                                @Nullable String[] requiredStateFilter) {
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
                requiredStateFilter,
                false
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
                                @Nullable String[] requiredStateFilter,
                                boolean explicitRecall) {
        boolean debugLag = isLagDebugEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        try {
            queueCoordinator.queue(
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
                    requiredStateFilter,
                    explicitRecall
            );
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
            String currentState = resolveCurrentStateName(ref, npc, store);
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
                if (!worldAccess.hasExpectedLiveOwner(store, ref, pending)) {
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

    private void finishRelocation(UUID npcUuid, PendingRelocation pending) {
        if (pendingByNpc.get(npcUuid) != pending) {
            return;
        }
        lastKnownByNpc.put(npcUuid, new Vector3d(pending.destination));
        removePending(npcUuid, pending);
    }

    void dropUnconfirmedRelocation(UUID npcUuid, PendingRelocation pending, long droppedAtMs) {
        terminalService.finish(npcUuid, pending, droppedAtMs, false);
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

    private void terminalizeRelocation(PendingRelocation pending, String reason) {
        if (pending.physicalMutationAttempted()) {
            dropUnconfirmedRelocation(pending.npcUuid, pending, System.currentTimeMillis());
            return;
        }
        removePending(pending.npcUuid, pending);
        logTravelDiagnostic(
                Level.WARNING,
                "Relocation terminalized for npc=" + pending.npcUuid + ", reason=" + reason
        );
    }

    void dropRetryExhausted(
            UUID npcUuid,
            PendingRelocation pending,
            long droppedAtMs
    ) {
        terminalService.finish(npcUuid, pending, droppedAtMs, true);
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
            dropUnconfirmedRelocation(npcUuid, pending, System.currentTimeMillis());
            return;
        }
        terminalizeRelocation(pending, "relocation-confirmation-dispatch-rejected");
    }

    @Nullable
    private String resolveCurrentStateName(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable NPCEntity npc,
                                           @Nullable Store<EntityStore> store) {
        if (npc == null || npc.getRole() == null || npcRef == null || store == null) {
            return null;
        }
        StateSupport stateSupport = NpcSupportAccess.state(npc.getRole(), npcRef, store);
        if (stateSupport == null) {
            return null;
        }
        String state = stateSupport.getStateName();
        return state != null && !state.isBlank() ? state : null;
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
