package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Maintains process-local detail for relocation drops until canonical lost state is projected.
 *
 * <p>The replacement dormant author is the sole durable lost authority. This cache has no file,
 * repository, recovery-operation, alias, or suppression behavior; command status and restoration
 * read the canonical profile projection.</p>
 */
public final class CommandLinkedNpcLostService {
    private final ConcurrentHashMap<UUID, LostLinkedNpcSnapshot> snapshotsByNpc =
            new ConcurrentHashMap<>();
    @Nullable
    private final HytaleLogger logger;
    @Nullable
    private final CommandLinkedNpcCaptureService captureService;
    @Nullable
    private final CommandLinkedNpcCoopService coopService;

    public CommandLinkedNpcLostService() {
        this(null, null, null);
    }

    public CommandLinkedNpcLostService(
            @Nullable HytaleLogger logger,
            @Nullable CommandLinkedNpcCaptureService captureService,
            @Nullable CommandLinkedNpcCoopService coopService
    ) {
        this.logger = logger;
        this.captureService = captureService;
        this.coopService = coopService;
    }

    /**
     * Records non-authoritative UI detail after relocation has emitted authoritative lost evidence.
     */
    public boolean recordLostFromRelocationDrop(
            UUID npcUuid,
            @Nullable UUID ownerUuid,
            @Nullable Vector3d sourceHintPosition,
            @Nullable Vector3d alternateSourceHintPosition,
            @Nullable Vector3d destination,
            long queuedAtMs,
            long droppedAtMs,
            int retryAttempts
    ) {
        if (npcUuid == null || unavailableElsewhere(npcUuid)) {
            clearLostSnapshot(npcUuid);
            return false;
        }
        long recordedAt = droppedAtMs != 0L
                ? droppedAtMs
                : System.currentTimeMillis();
        LostLinkedNpcSnapshot current = snapshotsByNpc.get(npcUuid);
        Vector3d position = firstPosition(
                sourceHintPosition,
                alternateSourceHintPosition,
                destination,
                current == null ? null : current.lastKnownPosition()
        );
        Vector3d home = firstPosition(
                alternateSourceHintPosition,
                current == null ? null : current.homePosition()
        );
        LostLinkedNpcSnapshot snapshot = new LostLinkedNpcSnapshot(
                npcUuid,
                position,
                home,
                queuedAtMs != 0L ? queuedAtMs : recordedAt,
                current == null
                        ? recordedAt
                        : current.lostAtMs(),
                Math.max(
                        Math.max(0, retryAttempts),
                        current == null
                                ? 0
                                : current.relocationRetryAttempts()
                ),
                null,
                0L
        );
        snapshotsByNpc.put(npcUuid, snapshot);
        if (logger != null) {
            logger.at(Level.FINE).log(
                    "Recorded process-local lost companion detail (npc="
                            + npcUuid + ", retries="
                            + snapshot.relocationRetryAttempts() + ")."
            );
        }
        return true;
    }

    /**
     * Clears stale process-local detail when a live source is observed again.
     */
    public void onNpcAdded(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(
                reference, NPCEntity.getComponentType()
        );
        if (npc != null) {
            clearLostSnapshot(npc.getUuid());
        }
    }

    /**
     * Lifecycle durability is owned by the replacement dormant-event bridge.
     */
    public void onNpcRemoved(
            Ref<EntityStore> reference,
            RemoveReason reason,
            Store<EntityStore> store
    ) {
        // Intentionally process-local: absence/removal is not inferred here.
    }

    @Nullable
    public LostLinkedNpcSnapshot getLostSnapshot(UUID npcUuid) {
        return npcUuid == null ? null : snapshotsByNpc.get(npcUuid);
    }

    public boolean isLost(UUID npcUuid) {
        return getLostSnapshot(npcUuid) != null;
    }

    public boolean isLostOrTransitionPending(UUID npcUuid) {
        return isLost(npcUuid);
    }

    public void clearLostSnapshot(UUID npcUuid) {
        if (npcUuid != null) {
            snapshotsByNpc.remove(npcUuid);
        }
    }

    private boolean unavailableElsewhere(UUID npcUuid) {
        return captureService != null
                && captureService.getCapturedSnapshot(npcUuid) != null
                || coopService != null
                && coopService.getCoopSnapshot(npcUuid) != null;
    }

    @Nullable
    private Vector3d firstPosition(@Nullable Vector3d... candidates) {
        for (Vector3d candidate : candidates) {
            if (candidate != null) {
                return new Vector3d(candidate);
            }
        }
        return null;
    }

    /**
     * Process-local presentation detail for one relocation drop.
     *
     * <p>The final two fields retain the released v1 snapshot vocabulary for canonical legacy
     * snapshot decoding; this service never authors replacement mappings.</p>
     */
    public record LostLinkedNpcSnapshot(
            UUID npcUuid,
            @Nullable Vector3d lastKnownPosition,
            @Nullable Vector3d homePosition,
            long lastRelocationQueuedAtMs,
            long lostAtMs,
            int relocationRetryAttempts,
            @Nullable UUID replacementNpcUuid,
            long recoveredAtMs
    ) {
        public LostLinkedNpcSnapshot {
            lastKnownPosition = lastKnownPosition == null
                    ? null
                    : new Vector3d(lastKnownPosition);
            homePosition = homePosition == null
                    ? null
                    : new Vector3d(homePosition);
            relocationRetryAttempts = Math.max(
                    0, relocationRetryAttempts
            );
        }

        @Override
        public Vector3d lastKnownPosition() {
            return lastKnownPosition == null
                    ? null
                    : new Vector3d(lastKnownPosition);
        }

        @Override
        public Vector3d homePosition() {
            return homePosition == null
                    ? null
                    : new Vector3d(homePosition);
        }

        public boolean isAwaitingRecovery() {
            return replacementNpcUuid == null;
        }
    }
}
