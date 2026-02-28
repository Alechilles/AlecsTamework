package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.Objects;
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

    public CommandNpcRelocationService() {
        this(null);
    }

    public CommandNpcRelocationService(@Nullable HytaleLogger logger) {
        this.logger = logger;
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
                    System.currentTimeMillis()
            );
            if (sourceHintPosition != null) {
                // Do not overwrite an existing tracked position with a potentially stale metadata hint.
                lastKnownByNpc.putIfAbsent(npcUuid, new Vector3d(sourceHintPosition));
            } else if (alternateSourceHintPosition != null) {
                lastKnownByNpc.putIfAbsent(npcUuid, new Vector3d(alternateSourceHintPosition));
            }
            pendingByNpc.put(npcUuid, pending);
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
        tryApply(world, snapshot.npcUuid);
    }

    public void onNpcRemoved(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        NpcSnapshot snapshot = resolveSnapshot(reference, store);
        if (snapshot == null || snapshot.npcUuid == null || snapshot.position == null) {
            return;
        }
        lastKnownByNpc.put(snapshot.npcUuid, snapshot.position);
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
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                pending.resetRelocationIssue();
                retryPending(world, npcUuid, pending);
                return false;
            }
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
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
            if (role != null && role.getMarkedEntitySupport() != null) {
                if (pending.clearLockedTarget) {
                    role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
                }
                if (pending.assignOwnerAsMasterTarget && pending.ownerUuid != null) {
                    Ref<EntityStore> ownerRef = world.getEntityRef(pending.ownerUuid);
                    if (ownerRef != null && ownerRef.isValid()) {
                        role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, ownerRef);
                    }
                }
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
            pendingByNpc.remove(npcUuid, pending);
            logRetryDrop(pending, now);
            return;
        }
        logRetryProgress(pending, now);
        requestChunksForPending(world, pending);
        scheduleTryApply(world, npcUuid, retryInterval);
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

    private boolean isLagDebugEnabled() {
        Tamework plugin = Tamework.getInstance();
        return plugin != null && plugin.isDebugLagEnabled();
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
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return null;
        }
        TransformComponent transform = store.getComponent(reference, TransformComponent.getComponentType());
        Vector3d position = transform != null ? new Vector3d(transform.getPosition()) : null;
        return new NpcSnapshot(npc.getUuid(), position);
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
        private final ConcurrentHashMap<Long, Long> lastChunkRequestAtMsByChunk = new ConcurrentHashMap<>();
        private long nextScheduledApplyAtMs;
        private boolean relocationIssued;
        private long relocationIssuedAtMs;
        private int retryAttempts;
        private int lastLoggedRetryAttempts;
        private long lastRetryCountedAtMs;

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
                                  long queuedAtMs) {
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
            this.nextScheduledApplyAtMs = Long.MAX_VALUE;
            this.relocationIssued = false;
            this.relocationIssuedAtMs = 0L;
            this.retryAttempts = 0;
            this.lastLoggedRetryAttempts = 0;
            this.lastRetryCountedAtMs = queuedAtMs;
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
    }
}
