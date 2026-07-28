package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Loads and leases the exact source and destination chunks needed by one queued relocation.
 *
 * <p>Source hints and the last observed location identify chunks worth loading while the live
 * entity remains authoritative.</p>
 */
final class CommandRelocationChunkRequestService implements AutoCloseable {
    private static final long CHUNK_REQUEST_COOLDOWN_MS = 1500L;
    private static final long APPLY_AFTER_LOAD_DELAY_MS = 250L;
    private final Map<UUID, PendingRelocation> pendingByNpc;
    private final Map<UUID, Vector3d> lastKnownByNpc;
    private final CommandRelocationWorldAccess worldAccess;
    private final CommandRelocationDiagnostics diagnostics;
    private final ApplyScheduler applyScheduler;
    private final CommandRelocationChunkLeaseService<PendingRelocation, WorldChunk> chunkLeases;

    CommandRelocationChunkRequestService(
            @Nonnull Map<UUID, PendingRelocation> pendingByNpc,
            @Nonnull Map<UUID, Vector3d> lastKnownByNpc,
            @Nonnull CommandRelocationWorldAccess worldAccess,
            @Nonnull CommandRelocationDiagnostics diagnostics,
            @Nonnull ApplyScheduler applyScheduler
    ) {
        this.pendingByNpc = Objects.requireNonNull(pendingByNpc, "pendingByNpc");
        this.lastKnownByNpc = Objects.requireNonNull(lastKnownByNpc, "lastKnownByNpc");
        this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.applyScheduler = Objects.requireNonNull(applyScheduler, "applyScheduler");
        this.chunkLeases = new CommandRelocationChunkLeaseService<>(
                WorldChunk::addKeepLoaded,
                WorldChunk::removeKeepLoaded
        );
    }

    void open(PendingRelocation pending) {
        chunkLeases.open(pending);
    }

    void release(PendingRelocation pending) {
        chunkLeases.release(pending);
    }

    void requestDestinationAndSource(World destinationWorld, PendingRelocation pending) {
        if (destinationWorld == null || pending == null) {
            return;
        }
        requestChunk(destinationWorld, destinationWorld, pending,
                worldAccess.toChunk(pending.destination.x),
                worldAccess.toChunk(pending.destination.z));
        requestSourceHints(destinationWorld, destinationWorld, pending);
    }

    void requestSource(World sourceWorld, World destinationWorld, PendingRelocation pending) {
        if (sourceWorld == null || destinationWorld == null || pending == null) {
            return;
        }
        requestSourceHints(sourceWorld, destinationWorld, pending);
    }

    boolean isDestinationReady(World destinationWorld, PendingRelocation pending) {
        if (destinationWorld == null || pending == null) {
            return false;
        }
        return pending.isChunkReady(
                destinationWorld.getName(),
                worldAccess.toChunk(pending.destination.x),
                worldAccess.toChunk(pending.destination.z)
        );
    }

    private void requestSourceHints(World sourceWorld,
                                    World destinationWorld,
                                    PendingRelocation pending) {
        Vector3d hintedSource = pending.sourceHintPosition;
        Vector3d alternateSource = pending.alternateSourceHintPosition;
        Vector3d cachedSource = lastKnownByNpc.get(pending.npcUuid);
        requestPositionIfPresent(sourceWorld, destinationWorld, pending, hintedSource);
        if (alternateSource != null
                && (hintedSource == null || !worldAccess.isNear(alternateSource, hintedSource, 0.5))) {
            requestPositionIfPresent(sourceWorld, destinationWorld, pending, alternateSource);
        }
        if (cachedSource != null
                && (hintedSource == null || !worldAccess.isNear(cachedSource, hintedSource, 0.5))
                && (alternateSource == null || !worldAccess.isNear(cachedSource, alternateSource, 0.5))) {
            requestPositionIfPresent(sourceWorld, destinationWorld, pending, cachedSource);
        }
    }

    private void requestPositionIfPresent(World sourceWorld,
                                          World destinationWorld,
                                          PendingRelocation pending,
                                          @Nullable Vector3d position) {
        if (position == null) {
            return;
        }
        requestChunk(sourceWorld, destinationWorld, pending,
                worldAccess.toChunk(position.x), worldAccess.toChunk(position.z));
    }

    private void requestChunk(World chunkWorld,
                              World destinationWorld,
                              PendingRelocation pending,
                              int chunkX,
                              int chunkZ) {
        String worldName = chunkWorld.getName();
        long now = System.currentTimeMillis();
        if (!pending.shouldRequestChunk(
                worldName, chunkX, chunkZ, now, CHUNK_REQUEST_COOLDOWN_MS)) {
            return;
        }
        chunkWorld.getChunkAsync(chunkX, chunkZ).whenComplete((chunk, failure) -> {
            if (pendingByNpc.get(pending.npcUuid) != pending) {
                return;
            }
            if (failure == null && chunk != null) {
                if (!chunkLeases.retain(pending, chunk)) {
                    diagnostics.chunkLeaseNotRetained(pending.npcUuid, chunkX, chunkZ);
                    return;
                }
                pending.markChunkReady(worldName, chunkX, chunkZ);
                applyScheduler.schedule(
                        destinationWorld, pending.npcUuid, APPLY_AFTER_LOAD_DELAY_MS);
                return;
            }
            diagnostics.chunkRequestFailed(pending.npcUuid, chunkX, chunkZ, failure);
        });
    }

    @Override
    public void close() {
        chunkLeases.close();
    }

    @FunctionalInterface
    interface ApplyScheduler {
        void schedule(@Nonnull World world, @Nonnull UUID npcUuid, long delayMs);
    }

}
