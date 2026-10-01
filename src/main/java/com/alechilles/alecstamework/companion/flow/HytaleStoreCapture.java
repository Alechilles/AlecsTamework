package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@link StoreFlow}'s capture of a loaded body: a snapshot and summary taken in a task on the
 * body's world thread. Safe to call from any thread. Completes null when the body is gone from
 * its world or the world no longer accepts tasks, and exceptionally when the snapshot fails or
 * the world does not run the task within {@link #CAPTURE_TIMEOUT_MS}; the companion then stays
 * live. A task that runs after the timeout only builds a document nobody reads.
 */
public final class HytaleStoreCapture implements StoreFlow.BodyCapture<Ref<EntityStore>> {
    static final long CAPTURE_TIMEOUT_MS = 5_000L;

    private final CompanionSnapshots snapshots;
    private final CompanionSummaries summaries;

    public HytaleStoreCapture(@Nonnull CompanionSnapshots snapshots, @Nonnull CompanionSummaries summaries) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
    }

    @Override
    @Nonnull
    public CompletableFuture<StoreFlow.CapturedBody> capture(@Nonnull Ref<EntityStore> body) {
        World world = CompanionBodies.worldOf(body);
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<StoreFlow.CapturedBody> captured = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    captured.complete(captureOnWorldThread(world, body));
                } catch (RuntimeException | LinkageError failure) {
                    captured.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            return CompletableFuture.completedFuture(null);
        }
        return captured.orTimeout(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Nullable
    private StoreFlow.CapturedBody captureOnWorldThread(World world, Ref<EntityStore> body) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (!body.isValid() || body.getStore() != store) {
            return null;
        }
        TameworkCompanionComponent stamp = store.getComponent(body, TameworkCompanionComponent.getComponentType());
        if (stamp == null || stamp.getProfileId() == null) {
            return null;
        }
        // The flow re-stamps the data at the stored generation; the envelope's own fields are not used.
        SnapshotEnvelope envelope = snapshots.capture(body, store, stamp.getProfileId(), stamp.getGeneration(),
                world.getName(), CompanionWorldTime.gameTimeMs(store));
        if (envelope == null) {
            throw new IllegalStateException("Snapshot failed for companion " + stamp.getProfileId());
        }
        CompanionSummary summary = summaries.capture(body, store, System.currentTimeMillis());
        return new StoreFlow.CapturedBody(envelope.data(), summary == null ? CompanionSummary.EMPTY : summary);
    }
}
