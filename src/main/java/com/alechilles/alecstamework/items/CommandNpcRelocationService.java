package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Handles delayed/off-screen NPC relocation requests for command items.
 */
public final class CommandNpcRelocationService {
    private static final long SLOW_OPERATION_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(20L);
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final int CHUNK_SIZE = 32;
    private static final long INITIAL_APPLY_DELAY_MS = 250L;
    private static final long CHUNK_REQUEST_COOLDOWN_MS = 1500L;
    private static final long RELOCATION_CONFIRMATION_DELAY_MS = 250L;
    private static final long RELOCATION_CONFIRMATION_TIMEOUT_MS = 5000L;
    private static final double DESTINATION_CONFIRM_TOLERANCE = 4.0;
    private static final long RETRY_INTERVAL_MS = 2000L;
    private static final long MAX_RELOCATION_WAIT_MS = 120000L;
    private static final int MAX_RETRY_ATTEMPTS = 60;
    private static final int RETRY_PROGRESS_LOG_STEP = 5;

    @Nullable
    private final HytaleLogger logger;
    private final ConcurrentHashMap<UUID, PendingRelocation> pendingByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Vector3d> lastKnownByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, World> knownWorldByNpc = new ConcurrentHashMap<>();
    @Nullable
    private volatile CommandRelocationDropListener relocationDropListener;

    public CommandNpcRelocationService() {
        this(null);
    }

    public CommandNpcRelocationService(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    public void setRelocationDropListener(@Nullable CommandRelocationDropListener relocationDropListener) {
        this.relocationDropListener = relocationDropListener;
    }

    public void cancelPendingRelocation(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        PendingRelocation pending = pendingByNpc.remove(npcUuid);
        if (pending != null) {
            pending.markCrossWorldTransferFinished();
            logTravelDiagnostic(
                    Level.INFO,
                    "Cancelled pending relocation for npc=" + npcUuid + " due to explicit lifecycle transition."
            );
        }
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
            pendingByNpc.put(npcUuid, pending);
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
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NpcSnapshot snapshot = resolveSnapshot(reference, store);
        if (snapshot == null || snapshot.npcUuid == null) {
            return;
        }
        if (snapshot.position != null) {
            lastKnownByNpc.put(snapshot.npcUuid, snapshot.position);
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return;
        }
        knownWorldByNpc.put(snapshot.npcUuid, world);
        tryApply(world, snapshot.npcUuid);
    }

    public void onNpcRemoved(Ref<EntityStore> reference, RemoveReason reason, Store<EntityStore> store) {
        if (reference == null || reason == null || store == null) {
            return;
        }
        NpcSnapshot snapshot = resolveSnapshot(reference, store);
        if (snapshot == null || snapshot.npcUuid == null) {
            return;
        }
        if (snapshot.position != null) {
            lastKnownByNpc.put(snapshot.npcUuid, snapshot.position);
        }
        if (reason == RemoveReason.REMOVE) {
            knownWorldByNpc.remove(snapshot.npcUuid);
            return;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world != null) {
            knownWorldByNpc.put(snapshot.npcUuid, world);
        }
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
                pending.resetRelocationIssue();
                retryPending(world, npcUuid, pending);
                return false;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                pending.resetRelocationIssue();
                retryPending(world, npcUuid, pending);
                return false;
            }
            NPCEntity npc = safeGetComponent(store, ref, NPCEntity.getComponentType());
            if (npc == null) {
                pending.resetRelocationIssue();
                retryPending(world, npcUuid, pending);
                return false;
            }
            knownWorldByNpc.put(npcUuid, world);
            String currentState = resolveCurrentStateName(npc);
            if (!pending.isStateAllowed(currentState)) {
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
                pendingByNpc.remove(npcUuid, pending);
                return false;
            }
            TransformComponent transform = safeGetComponent(store, ref, TransformComponent.getComponentType());
            if (transform == null) {
                pending.resetRelocationIssue();
                retryPending(world, npcUuid, pending);
                return false;
            }
            if (!pending.relocationIssued) {
                npc.moveTo(ref, pending.destination.x, pending.destination.y, pending.destination.z, store);
                pending.markRelocationIssued(now);
                scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
                return false;
            }
            Vector3d currentPosition = new Vector3d(transform.getPosition());
            if (!isAtDestination(currentPosition, pending.destination, DESTINATION_CONFIRM_TOLERANCE)) {
                if (now - pending.relocationIssuedAtMs > RELOCATION_CONFIRMATION_TIMEOUT_MS) {
                    pending.resetRelocationIssue();
                    retryPending(world, npcUuid, pending);
                } else {
                    scheduleTryApply(world, npcUuid, RELOCATION_CONFIRMATION_DELAY_MS);
                }
                return false;
            }
            Role role = npc.getRole();
            boolean waitingForOwnerTarget = false;
            if (role != null && role.getMarkedEntitySupport() != null) {
                if (pending.clearLockedTarget) {
                    role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
                }
                if (pending.assignOwnerAsMasterTarget && pending.ownerUuid != null) {
                    Ref<EntityStore> ownerRef = world.getEntityRef(pending.ownerUuid);
                    if (ownerRef != null && ownerRef.isValid()) {
                        role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, ownerRef);
                    } else {
                        waitingForOwnerTarget = true;
                    }
                }
            }
            if (waitingForOwnerTarget) {
                retryPending(world, npcUuid, pending);
                return false;
            }
            if (pending.state != null && !pending.state.isBlank()) {
                applyState(role, ref, store, pending.state, pending.subState);
            }
            lastKnownByNpc.put(npcUuid, new Vector3d(pending.destination));
            pendingByNpc.remove(npcUuid, pending);
            return true;
        } finally {
            if (debugLag) {
                logSlowOperation(startedNs, "relocation.tryApply npc=" + npcUuid);
            }
        }
    }

    private void retryPending(World world, UUID npcUuid, PendingRelocation pending) {
        if (world == null || npcUuid == null || pending == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long retryInterval = Math.max(250L, resolveRetryIntervalMs());
        if (now - pending.lastRetryCountedAtMs >= retryInterval) {
            pending.retryAttempts++;
            pending.lastRetryCountedAtMs = now;
        }
        if (now - pending.queuedAtMs > resolveMaxRelocationWaitMs()
                || pending.retryAttempts > resolveMaxRetryAttempts()) {
            dropPendingAsLost(npcUuid, pending, now);
            return;
        }
        logRetryProgress(pending, now);
        requestChunksForPending(world, pending);
        scheduleTryApply(world, npcUuid, retryInterval);
    }

    private boolean maybeStartCrossWorldTransfer(World destinationWorld,
                                                 UUID npcUuid,
                                                 PendingRelocation pending) {
        if (destinationWorld == null || npcUuid == null || pending == null || !pending.allowCrossWorldTransfer) {
            return false;
        }
        World sourceWorld = knownWorldByNpc.get(npcUuid);
        if (sourceWorld == null || isSameWorld(sourceWorld, destinationWorld)) {
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
        try {
            sourceWorld.execute(() -> transferPendingAcrossWorlds(sourceWorld, destinationWorld, npcUuid, pending));
        } catch (RuntimeException ex) {
            pending.markCrossWorldTransferFinished();
            pending.resetRelocationIssue();
            knownWorldByNpc.remove(npcUuid, sourceWorld);
            logTravelDiagnostic(
                    Level.WARNING,
                    "Unable to start cross-world transfer for npc="
                            + npcUuid
                            + ": source world is no longer accepting tasks (sourceWorld="
                            + sourceWorld.getName()
                            + ", destinationWorld="
                            + destinationWorld.getName()
                            + ", reason="
                            + ex.getClass().getSimpleName()
                            + ": "
                            + ex.getMessage()
                            + ")"
            );
            return false;
        }
        return true;
    }

    private boolean isSameWorld(@Nullable World left, @Nullable World right) {
        if (left == null || right == null) {
            return false;
        }
        if (left == right) {
            return true;
        }
        String leftName = left.getName();
        String rightName = right.getName();
        return leftName != null && leftName.equals(rightName);
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
        if (isSameWorld(sourceWorld, destinationWorld)) {
            pending.markCrossWorldTransferFinished();
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
            destinationWorld.execute(() -> applyTransferFailurePolicy(destinationWorld, npcUuid, pending));
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
            destinationWorld.execute(() -> {
                if (pendingByNpc.get(npcUuid) != pending) {
                    return;
                }
                pending.markCrossWorldTransferFinished();
                pending.resetRelocationIssue();
                retryPending(destinationWorld, npcUuid, pending);
            });
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
            destinationWorld.execute(() -> applyTransferFailurePolicy(destinationWorld, npcUuid, pending));
            return;
        }
        if (isUnsafeCrossWorldTransferState(sourceStore, sourceRef, sourceNpc)) {
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
            destinationWorld.execute(() -> {
                if (pendingByNpc.get(npcUuid) != pending) {
                    return;
                }
                pending.markCrossWorldTransferFinished();
                pending.resetRelocationIssue();
                retryPending(destinationWorld, npcUuid, pending);
            });
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
            destinationWorld.execute(() -> cancelPendingForStateFilter(npcUuid, pending));
            return;
        }
        Holder<EntityStore> drainedHolder = sourceStore.removeEntity(sourceRef, RemoveReason.UNLOAD);
        if (drainedHolder == null) {
            logTravelDiagnostic(
                    Level.WARNING,
                    "Cross-world transfer failed while removing source entity for npc="
                            + npcUuid
                            + ", sourceWorld="
                            + sourceWorld.getName()
            );
            destinationWorld.execute(() -> applyTransferFailurePolicy(destinationWorld, npcUuid, pending));
            return;
        }
        destinationWorld.execute(() -> {
            if (pendingByNpc.get(npcUuid) != pending) {
                logTravelDiagnostic(
                        Level.INFO,
                        "Cross-world transfer aborted because pending relocation was replaced/cleared for npc=" + npcUuid
                );
                sourceWorld.execute(() -> restoreSourceEntity(sourceWorld, sourceStore, drainedHolder, npcUuid));
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
                if (isEntityPresentInWorld(destinationWorld, npcUuid)) {
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
            if (destinationRef == null || !destinationRef.isValid()) {
                if (isEntityPresentInWorld(destinationWorld, npcUuid)) {
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
        });
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
            applyTransferFailurePolicy(destinationWorld, npcUuid, pending);
            return;
        }
        sourceWorld.execute(() -> {
            restoreSourceEntity(sourceWorld, sourceStore, drainedHolder, npcUuid);
            destinationWorld.execute(() -> applyTransferFailurePolicy(destinationWorld, npcUuid, pending));
        });
    }

    private void restoreSourceEntity(World sourceWorld,
                                     @Nullable Store<EntityStore> sourceStore,
                                     @Nullable Holder<EntityStore> drainedHolder,
                                     @Nullable UUID npcUuid) {
        if (sourceWorld == null || sourceStore == null || drainedHolder == null) {
            return;
        }
        if (npcUuid != null && isEntityPresentInWorld(sourceWorld, npcUuid)) {
            knownWorldByNpc.put(npcUuid, sourceWorld);
            return;
        }
        try {
            Ref<EntityStore> restored = sourceStore.addEntity(drainedHolder, AddReason.SPAWN);
            if (npcUuid != null && restored != null && restored.isValid()) {
                knownWorldByNpc.put(npcUuid, sourceWorld);
            }
        } catch (Exception ex) {
            logTravelDiagnostic(
                    Level.WARNING,
                    "Cross-world restore skipped for npc="
                            + npcUuid
                            + ", sourceWorld="
                            + sourceWorld.getName()
                            + ", reason="
                            + ex.getClass().getSimpleName()
                            + ": "
                            + ex.getMessage()
            );
        }
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
            pendingByNpc.remove(npcUuid, pending);
            return;
        }
        if (policy == TwCompanionConfig.TransferFailurePolicy.MarkLost) {
            dropPendingAsLost(npcUuid, pending, System.currentTimeMillis());
            return;
        }
        pending.resetRelocationIssue();
        retryPending(world, npcUuid, pending);
    }

    private void cancelPendingForStateFilter(UUID npcUuid, PendingRelocation pending) {
        if (npcUuid == null || pending == null) {
            return;
        }
        pending.markCrossWorldTransferFinished();
        pendingByNpc.remove(npcUuid, pending);
        logTravelDiagnostic(
                Level.INFO,
                "Cancelled relocation due to state filter for npc="
                        + npcUuid
                        + ", requiredStateFilter="
                        + pending.describeStateFilter()
        );
    }

    private void dropPendingAsLost(UUID npcUuid, PendingRelocation pending, long droppedAtMs) {
        if (npcUuid == null || pending == null) {
            return;
        }
        pendingByNpc.remove(npcUuid, pending);
        CommandRelocationDropListener dropListener = relocationDropListener;
        if (dropListener != null) {
            dropListener.onRelocationDropped(
                    pending.npcUuid,
                    pending.ownerUuid,
                    pending.sourceHintPosition,
                    pending.alternateSourceHintPosition,
                    pending.destination,
                    pending.queuedAtMs,
                    droppedAtMs,
                    pending.retryAttempts
            );
        }
        logTravelDiagnostic(
                Level.WARNING,
                "Dropped relocation as lost for npc="
                        + pending.npcUuid
                        + ", retries="
                        + pending.retryAttempts
                        + ", ageMs="
                        + (droppedAtMs - pending.queuedAtMs)
        );
        logRetryDrop(pending, droppedAtMs);
    }

    private void requestChunksForPending(World world, PendingRelocation pending) {
        if (world == null || pending == null) {
            return;
        }
        requestChunkLoad(world, pending, pending.destination);
        Vector3d hintedSource = pending.sourceHintPosition;
        Vector3d alternateSource = pending.alternateSourceHintPosition;
        Vector3d cachedSource = lastKnownByNpc.get(pending.npcUuid);
        if (hintedSource != null) {
            requestChunkLoad(world, pending, hintedSource);
        }
        if (alternateSource != null
                && (hintedSource == null || !isNear(alternateSource, hintedSource, 0.5))) {
            requestChunkLoad(world, pending, alternateSource);
        }
        if (cachedSource != null
                && (hintedSource == null || !isNear(cachedSource, hintedSource, 0.5))
                && (alternateSource == null || !isNear(cachedSource, alternateSource, 0.5))) {
            requestChunkLoad(world, pending, cachedSource);
        }
    }

    private boolean isNear(Vector3d left, Vector3d right, double tolerance) {
        if (left == null || right == null) {
            return false;
        }
        double dx = left.x - right.x;
        double dy = left.y - right.y;
        double dz = left.z - right.z;
        return (dx * dx + dy * dy + dz * dz) <= (tolerance * tolerance);
    }

    private boolean isAtDestination(Vector3d current, Vector3d destination, double tolerance) {
        if (current == null || destination == null) {
            return false;
        }
        return isNear(current, destination, tolerance);
    }

    private void requestChunkLoad(World world, PendingRelocation pending, Vector3d position) {
        if (world == null || pending == null || position == null) {
            return;
        }
        int chunkX = toChunk(position.x);
        int chunkZ = toChunk(position.z);
        long chunkKey = toChunkKey(chunkX, chunkZ);
        long now = System.currentTimeMillis();
        if (!pending.shouldRequestChunk(chunkKey, now, CHUNK_REQUEST_COOLDOWN_MS)) {
            return;
        }
        CompletableFuture<?> request = world.getChunkAsync(chunkX, chunkZ);
        request.whenComplete((chunk, throwable) -> {
            if (pendingByNpc.get(pending.npcUuid) != pending) {
                return;
            }
            if (throwable == null) {
                scheduleTryApply(world, pending.npcUuid, INITIAL_APPLY_DELAY_MS);
            } else if (isLagDebugEnabled() && logger != null) {
                logger.at(Level.WARNING).withCause(throwable).log(
                        "Tamework lag probe: relocation chunk request failed (npc="
                                + pending.npcUuid
                                + ", chunkX="
                                + chunkX
                                + ", chunkZ="
                                + chunkZ
                                + ")."
                );
            }
        });
    }

    private void scheduleTryApply(World world, UUID npcUuid, long delayMs) {
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
        CompletableFuture.runAsync(
                () -> world.execute(() -> tryApplyIfScheduled(world, npcUuid, pending, dueAtMs)),
                CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS)
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

    private void applyState(Role role,
                            Ref<EntityStore> npcRef,
                            Store<EntityStore> store,
                            String state,
                            @Nullable String subState) {
        if (role == null || role.getStateSupport() == null || state == null || state.isBlank()) {
            return;
        }
        StateSupport support = role.getStateSupport();
        String resolvedSubState = subState;
        if (support.getStateHelper() != null) {
            int stateIndex = support.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                return;
            }
            if (resolvedSubState == null || resolvedSubState.isBlank()) {
                resolvedSubState = support.getStateHelper().getDefaultSubState();
            } else if (support.getStateHelper().getSubStateIndex(stateIndex, resolvedSubState) == StateSupport.NO_STATE) {
                return;
            }
        }
        support.setState(npcRef, state, resolvedSubState == null ? "" : resolvedSubState, store);
    }

    @Nullable
    private String resolveCurrentStateName(@Nullable NPCEntity npc) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        String state = npc.getRole().getStateSupport().getStateName();
        return state != null && !state.isBlank() ? state : null;
    }

    @Nullable
    private static String normalizeStateKey(@Nullable String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return state.trim().toLowerCase(Locale.ROOT);
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

    private static String describeStateFilter(@Nullable String[] stateFilter) {
        if (stateFilter == null || stateFilter.length == 0) {
            return "[]";
        }
        return Arrays.toString(stateFilter);
    }

    private static boolean matchesStateFilter(String normalizedState, String normalizedFilter) {
        if (normalizedState.equals(normalizedFilter) || normalizedState.startsWith(normalizedFilter)) {
            return true;
        }
        String[] segments = normalizedState.split("[^a-z0-9]+");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (segment.equals(normalizedFilter) || segment.startsWith(normalizedFilter)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLagDebugEnabled() {
        Tamework plugin = Tamework.getInstance();
        return plugin != null && plugin.isDebugLagEnabled();
    }

    private void logTravelDiagnostic(Level level, String message) {
        if (logger == null || message == null || message.isBlank()) {
            return;
        }
        logger.at(level).log("[CompanionTravel] " + message);
    }

    private void logSlowOperation(long startedNs, String operation) {
        if (startedNs <= 0L || logger == null) {
            return;
        }
        long elapsedNs = System.nanoTime() - startedNs;
        if (elapsedNs < SLOW_OPERATION_THRESHOLD_NS) {
            return;
        }
        double elapsedMs = elapsedNs / 1_000_000.0;
        logger.at(Level.WARNING).log(
                "Tamework lag probe: "
                        + operation
                        + " took "
                        + elapsedMs
                        + "ms."
        );
    }

    private void logRetryProgress(PendingRelocation pending, long nowMs) {
        if (pending == null || logger == null || !isLagDebugEnabled()) {
            return;
        }
        if (!pending.markRetryProgressLogged(RETRY_PROGRESS_LOG_STEP)) {
            return;
        }
        logger.at(Level.INFO).log(
                "Tamework lag probe: relocation still pending (npc="
                        + pending.npcUuid
                        + ", retries="
                        + pending.retryAttempts
                        + ", ageMs="
                        + (nowMs - pending.queuedAtMs)
                        + ")."
        );
    }

    private void logRetryDrop(PendingRelocation pending, long nowMs) {
        if (pending == null || logger == null || !isLagDebugEnabled()) {
            return;
        }
        logger.at(Level.WARNING).log(
                "Tamework lag probe: dropping relocation after retries (npc="
                        + pending.npcUuid
                        + ", retries="
                        + pending.retryAttempts
                        + ", ageMs="
                        + (nowMs - pending.queuedAtMs)
                        + ")."
        );
    }

    private long resolveRetryIntervalMs() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        long configured = config != null ? config.getCommandRelocationRetryIntervalMs() : 0L;
        return configured > 0L ? configured : RETRY_INTERVAL_MS;
    }

    private long resolveMaxRelocationWaitMs() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        long configured = config != null ? config.getCommandRelocationMaxWaitMs() : 0L;
        return configured > 0L ? configured : MAX_RELOCATION_WAIT_MS;
    }

    private int resolveMaxRetryAttempts() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        int configured = config != null ? config.getCommandRelocationMaxRetryAttempts() : 0;
        return configured > 0 ? configured : MAX_RETRY_ATTEMPTS;
    }

    private int toChunk(double coord) {
        return Math.floorDiv((int) Math.floor(coord), CHUNK_SIZE);
    }

    private long toChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private NpcSnapshot resolveSnapshot(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return null;
        }
        NPCEntity npc = safeGetComponent(store, reference, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return null;
        }
        TransformComponent transform = safeGetComponent(store, reference, TransformComponent.getComponentType());
        Vector3d position = transform != null ? new Vector3d(transform.getPosition()) : null;
        return new NpcSnapshot(npc.getUuid(), position);
    }

    private boolean isEntityPresentInWorld(@Nullable World world, @Nullable UUID npcUuid) {
        if (world == null || npcUuid == null) {
            return false;
        }
        Ref<EntityStore> ref = world.getEntityRef(npcUuid);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return false;
        }
        return safeGetComponent(store, ref, NPCEntity.getComponentType()) != null;
    }

    private boolean isUnsafeCrossWorldTransferState(@Nullable Store<EntityStore> sourceStore,
                                                    @Nullable Ref<EntityStore> sourceRef,
                                                    @Nullable NPCEntity sourceNpc) {
        if (sourceStore == null || sourceRef == null || !sourceRef.isValid() || sourceNpc == null) {
            return true;
        }
        if (sourceNpc.getRole() == null) {
            return true;
        }
        ComponentType<EntityStore, NPCMountComponent> mountType = NPCMountComponent.getComponentType();
        return mountType != null && safeGetComponent(sourceStore, sourceRef, mountType) != null;
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

    private static final class NpcSnapshot {
        private final UUID npcUuid;
        private final Vector3d position;

        private NpcSnapshot(UUID npcUuid, Vector3d position) {
            this.npcUuid = npcUuid;
            this.position = position;
        }
    }

    private static final class PendingRelocation {
        private final UUID npcUuid;
        private final Vector3d destination;
        private final Vector3d sourceHintPosition;
        private final Vector3d alternateSourceHintPosition;
        private final UUID ownerUuid;
        private final boolean assignOwnerAsMasterTarget;
        private final boolean clearLockedTarget;
        private final String state;
        private final String subState;
        private final long executeAfterMs;
        private final long queuedAtMs;
        private final boolean allowCrossWorldTransfer;
        private final TwCompanionConfig.TransferFailurePolicy onTransferFailure;
        private final Set<String> requiredStateFilter;
        private final ConcurrentHashMap<Long, Long> lastChunkRequestAtMsByChunk = new ConcurrentHashMap<>();
        private long nextScheduledApplyAtMs;
        private boolean relocationIssued;
        private long relocationIssuedAtMs;
        private int retryAttempts;
        private int lastLoggedRetryAttempts;
        private long lastRetryCountedAtMs;
        private boolean crossWorldTransferInProgress;
        private boolean sourceWorldMissingLogged;

        private PendingRelocation(UUID npcUuid,
                                  Vector3d destination,
                                  Vector3d sourceHintPosition,
                                  Vector3d alternateSourceHintPosition,
                                  UUID ownerUuid,
                                  boolean assignOwnerAsMasterTarget,
                                  boolean clearLockedTarget,
                                  String state,
                                  String subState,
                                  long executeAfterMs,
                                  long queuedAtMs,
                                  boolean allowCrossWorldTransfer,
                                  @Nullable TwCompanionConfig.TransferFailurePolicy onTransferFailure,
                                  @Nullable String[] requiredStateFilter) {
            this.npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
            this.destination = Objects.requireNonNull(destination, "destination");
            this.sourceHintPosition = sourceHintPosition;
            this.alternateSourceHintPosition = alternateSourceHintPosition;
            this.ownerUuid = ownerUuid;
            this.assignOwnerAsMasterTarget = assignOwnerAsMasterTarget;
            this.clearLockedTarget = clearLockedTarget;
            this.state = state;
            this.subState = subState;
            this.executeAfterMs = executeAfterMs;
            this.queuedAtMs = queuedAtMs;
            this.allowCrossWorldTransfer = allowCrossWorldTransfer;
            this.onTransferFailure = onTransferFailure != null
                    ? onTransferFailure
                    : TwCompanionConfig.TransferFailurePolicy.QueueForRecall;
            this.requiredStateFilter = normalizeStateFilter(requiredStateFilter);
            this.nextScheduledApplyAtMs = Long.MAX_VALUE;
            this.relocationIssued = false;
            this.relocationIssuedAtMs = 0L;
            this.retryAttempts = 0;
            this.lastLoggedRetryAttempts = 0;
            this.lastRetryCountedAtMs = queuedAtMs;
            this.crossWorldTransferInProgress = false;
            this.sourceWorldMissingLogged = false;
        }

        private boolean shouldRequestChunk(long chunkKey, long nowMs, long cooldownMs) {
            Long lastRequestAtMs = lastChunkRequestAtMsByChunk.get(chunkKey);
            if (lastRequestAtMs != null && nowMs - lastRequestAtMs < cooldownMs) {
                return false;
            }
            lastChunkRequestAtMsByChunk.put(chunkKey, nowMs);
            return true;
        }

        private synchronized boolean reserveScheduledApply(long dueAtMs) {
            if (dueAtMs >= nextScheduledApplyAtMs) {
                return false;
            }
            nextScheduledApplyAtMs = dueAtMs;
            return true;
        }

        private synchronized boolean consumeScheduledApply(long dueAtMs) {
            if (nextScheduledApplyAtMs != dueAtMs) {
                return false;
            }
            nextScheduledApplyAtMs = Long.MAX_VALUE;
            return true;
        }

        private synchronized boolean markRetryProgressLogged(int retryStep) {
            if (retryStep <= 0 || retryAttempts <= 0) {
                return false;
            }
            if (retryAttempts % retryStep != 0 || retryAttempts == lastLoggedRetryAttempts) {
                return false;
            }
            lastLoggedRetryAttempts = retryAttempts;
            return true;
        }

        private void markRelocationIssued(long nowMs) {
            this.relocationIssued = true;
            this.relocationIssuedAtMs = nowMs;
        }

        private void resetRelocationIssue() {
            this.relocationIssued = false;
            this.relocationIssuedAtMs = 0L;
        }

        private synchronized boolean markCrossWorldTransferStarted() {
            if (crossWorldTransferInProgress) {
                return false;
            }
            crossWorldTransferInProgress = true;
            return true;
        }

        private synchronized void markCrossWorldTransferFinished() {
            crossWorldTransferInProgress = false;
        }

        private synchronized boolean isCrossWorldTransferInProgress() {
            return crossWorldTransferInProgress;
        }

        private synchronized boolean markSourceWorldMissingLogged() {
            if (sourceWorldMissingLogged) {
                return false;
            }
            sourceWorldMissingLogged = true;
            return true;
        }

        private synchronized void resetSourceWorldMissingLogged() {
            sourceWorldMissingLogged = false;
        }

        private boolean isStateAllowed(@Nullable String stateName) {
            if (requiredStateFilter.isEmpty()) {
                return true;
            }
            String normalizedState = normalizeStateKey(stateName);
            if (normalizedState == null) {
                return false;
            }
            for (String requiredState : requiredStateFilter) {
                if (requiredState == null || requiredState.isBlank()) {
                    continue;
                }
                if (matchesStateFilter(normalizedState, requiredState)) {
                    return true;
                }
            }
            return false;
        }

        private String describeStateFilter() {
            return requiredStateFilter.isEmpty() ? "[]" : requiredStateFilter.toString();
        }
    }

    private void requestSourceChunksForPending(World sourceWorld, PendingRelocation pending) {
        if (sourceWorld == null || pending == null) {
            return;
        }
        Vector3d hintedSource = pending.sourceHintPosition;
        Vector3d alternateSource = pending.alternateSourceHintPosition;
        Vector3d cachedSource = lastKnownByNpc.get(pending.npcUuid);
        if (hintedSource != null) {
            requestChunkLoad(sourceWorld, pending, hintedSource);
        }
        if (alternateSource != null
                && (hintedSource == null || !isNear(alternateSource, hintedSource, 0.5))) {
            requestChunkLoad(sourceWorld, pending, alternateSource);
        }
        if (cachedSource != null
                && (hintedSource == null || !isNear(cachedSource, hintedSource, 0.5))
                && (alternateSource == null || !isNear(cachedSource, alternateSource, 0.5))) {
            requestChunkLoad(sourceWorld, pending, cachedSource);
        }
    }
}
