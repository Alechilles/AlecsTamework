package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The snapshot a restore uses (spec 8.5 step 3): a fresh one when the companion's body is loaded,
 * so the restore does not lose changes made since the last periodic snapshot, otherwise the
 * stored one.
 *
 * <p>{@link #read} returns at once and is safe from any thread, including a world thread. The
 * capture runs in a task on the body's world thread, which re-resolves the registered body and
 * queues the new snapshot to the writer. When the body is gone, the capture fails, the world does
 * not accept the task or the task does not run within {@link #CAPTURE_TIMEOUT_MS}, it falls back
 * to the stored snapshot, which is read off the world thread.
 */
public final class CompanionSnapshotSource {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** How long to wait for the body's world to run the capture before using the stored snapshot. */
    static final long CAPTURE_TIMEOUT_MS = 5_000L;

    private final Function<UUID, Ref<EntityStore>> loadedBody;
    private final Function<UUID, CompanionRecord> currentRecord;
    private final Consumer<SnapshotEnvelope> queueSnapshot;
    private final Function<UUID, CompletableFuture<SnapshotEnvelope>> storedSnapshot;
    private final CompanionSnapshots snapshots;

    /**
     * @param loadedBody     the registered, valid body of a profile, or null
     * @param currentRecord  the index's current record of a profile, or null
     * @param queueSnapshot  queues a captured snapshot to the writer
     * @param storedSnapshot the pending or written snapshot, read without blocking the caller
     */
    public CompanionSnapshotSource(@Nonnull Function<UUID, Ref<EntityStore>> loadedBody,
                                   @Nonnull Function<UUID, CompanionRecord> currentRecord,
                                   @Nonnull Consumer<SnapshotEnvelope> queueSnapshot,
                                   @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> storedSnapshot,
                                   @Nonnull CompanionSnapshots snapshots) {
        this.loadedBody = Objects.requireNonNull(loadedBody, "loadedBody");
        this.currentRecord = Objects.requireNonNull(currentRecord, "currentRecord");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.storedSnapshot = Objects.requireNonNull(storedSnapshot, "storedSnapshot");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /** Completes with the snapshot to restore from, or null when there is none. */
    @Nonnull
    public CompletableFuture<SnapshotEnvelope> read(@Nonnull UUID profileId) {
        Ref<EntityStore> body = loadedBody.apply(profileId);
        World world = body == null ? null : worldOf(body);
        if (world == null) {
            return storedSnapshot.apply(profileId);
        }
        CompletableFuture<SnapshotEnvelope> captured = new CompletableFuture<>();
        try {
            world.execute(() -> captured.complete(captureOnWorldThread(world, profileId)));
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            return storedSnapshot.apply(profileId);
        }
        // A task queued just before the world stopped may never run.
        return captured.completeOnTimeout(null, CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .thenCompose(fresh -> fresh != null ? CompletableFuture.completedFuture(fresh)
                        : storedSnapshot.apply(profileId));
    }

    /**
     * Captures the body still registered for the profile, at the record's generation, and queues
     * it. Returns null when there is no such body in this world or the capture failed (logged).
     */
    @Nullable
    private SnapshotEnvelope captureOnWorldThread(World world, UUID profileId) {
        try {
            Ref<EntityStore> ref = loadedBody.apply(profileId);
            CompanionRecord record = currentRecord.apply(profileId);
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (ref == null || record == null || ref.getStore() != store) {
                return null;
            }
            SnapshotEnvelope envelope = snapshots.capture(ref, store, profileId, record.generation(),
                    world.getName(), CompanionWorldTime.gameTimeMs(store));
            if (envelope != null) {
                queueSnapshot.accept(envelope);
            }
            return envelope;
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Fresh companion snapshot failed for profile %s; using the stored one", profileId);
            return null;
        }
    }

    @Nullable
    private static World worldOf(Ref<EntityStore> body) {
        Store<EntityStore> store = body.getStore();
        return store == null || store.getExternalData() == null ? null : store.getExternalData().getWorld();
    }
}
