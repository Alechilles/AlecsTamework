package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.compat.HytaleChunkAccess;
import com.alechilles.alecstamework.companion.flow.CompanionBodies;
import com.alechilles.alecstamework.companion.flow.CompanionWorldTime;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.items.CoopEffectService;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The world side of coop intake: reads coop slots, takes bodies in through {@link CoopIntakeFlow}
 * and writes slot entries. Reads run on the calling world thread; every write is queued with
 * {@code world.execute} and resolves its live refs there, so the schedule system and interactions
 * only read.
 *
 * <p>Codec-created interactions cannot be constructed with this, so the plugin installs one
 * instance while companion saving runs and removes it on shutdown.
 */
public final class HytaleCoopIntake implements CoopIntakeFlow.Coop<Ref<EntityStore>> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicReference<HytaleCoopIntake> CURRENT = new AtomicReference<>();
    /** How long to wait for the coop's world to run a slot write before undoing the intake. */
    private static final long SLOT_WRITE_TIMEOUT_MS = 5_000L;
    /** A body whose snapshot failed is not tried again for this long, so the sweep does not log every second. */
    private static final long SNAPSHOT_RETRY_MS = 60_000L;

    private final CompanionIndex index;
    private final CoopIntakeFlow<Ref<EntityStore>> flow;
    private final CompanionSnapshots snapshots;
    private final CompanionSummaries summaries;
    private final CoopEffectService effects = new CoopEffectService();
    private final Set<UUID> bodiesInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> snapshotFailedAt = new ConcurrentHashMap<>();

    public HytaleCoopIntake(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<Ref<EntityStore>> loaded,
                            @Nonnull BiConsumer<UUID, SnapshotEnvelope> queueSnapshot,
                            @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner,
                            @Nonnull CompanionSnapshots snapshots, @Nonnull CompanionSummaries summaries) {
        this.index = Objects.requireNonNull(index, "index");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.flow = new CoopIntakeFlow<>(index, loaded, queueSnapshot, flushOwner, this);
    }

    /** Makes {@code intake} the one interactions use. */
    public static void install(@Nonnull HytaleCoopIntake intake) {
        CURRENT.set(Objects.requireNonNull(intake, "intake"));
    }

    /** The installed intake, or null while companion saving is not running. */
    @Nullable
    public static HytaleCoopIntake current() {
        return CURRENT.get();
    }

    public static void uninstall() {
        CURRENT.set(null);
    }

    @Nullable
    public CompanionRecord record(@Nonnull UUID profileId) {
        return index.get(profileId);
    }

    /** The coop's slot entries; empty when it has none. Call on the world thread. */
    @Nonnull
    public List<TameworkCoopSlotsComponent.Slot> entries(@Nonnull World world, int x, int y, int z) {
        Block block = block(world, x, y, z);
        TameworkCoopSlotsComponent slots = block == null ? null : block.slots();
        return slots == null ? List.of() : slots.slots();
    }

    /** The lowest free slot that no intake holds, or -1. Call on the world thread. */
    public int firstFree(@Nonnull World world, int x, int y, int z, int maxResidents) {
        String name = world.getName();
        return CoopSlots.firstFree(entries(world, x, y, z), maxResidents, index::get, name, x, y, z,
                slot -> flow.reserved(name, x, y, z, slot));
    }

    /**
     * Whether the sweep may take this body in. A stamped body needs a LIVE record at its stamp; an
     * unstamped one must be unowned (an owned body gets stamped soon and is taken next time). Call
     * on the world thread.
     */
    public boolean canTakeIn(@Nonnull Ref<EntityStore> body, @Nonnull Store<EntityStore> store, @Nonnull UUID npcUuid) {
        return !bodiesInFlight.contains(npcUuid) && !recentlyFailed(npcUuid) && canTakeInNow(body, store);
    }

    /** Queues the intake of the NPC into {@code site} on its world thread. Call on the world thread. */
    public void takeIn(@Nonnull World world, @Nonnull CoopIntakeFlow.Site site, @Nonnull UUID npcUuid) {
        if (!bodiesInFlight.add(npcUuid)) {
            return;
        }
        try {
            world.execute(() -> {
                CompletableFuture<CoopIntakeFlow.Result> done;
                try {
                    done = takeInOnWorldThread(world, site, npcUuid);
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.at(Level.WARNING).withCause(failure).log("Coop intake of NPC %s failed", npcUuid);
                    done = CompletableFuture.completedFuture(CoopIntakeFlow.Result.COMMIT_FAILED);
                }
                done.whenComplete((result, error) -> bodiesInFlight.remove(npcUuid));
            });
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            bodiesInFlight.remove(npcUuid);
        }
    }

    /**
     * Starts the intake of a capture item into the first free slot of the coop. Call on the coop's
     * world thread. Returns null when the coop has no free slot.
     */
    @Nullable
    public CompletableFuture<CoopIntakeFlow.Result> offerItem(@Nonnull World world, @Nullable String coopId,
                                                            int x, int y, int z, int maxResidents,
                                                            @Nonnull CaptureItemKeys.Ref item,
                                                            @Nonnull Runnable consumeItem) {
        int slot = firstFree(world, x, y, z, maxResidents);
        if (slot < 0) {
            return null;
        }
        CoopIntakeFlow.Site site = new CoopIntakeFlow.Site(world.getName(), x, y, z, slot, coopId);
        return flow.intakeItem(new CoopIntakeFlow.ItemIntake(item.profileId(), item.generation(), site, consumeItem));
    }

    private CompletableFuture<CoopIntakeFlow.Result> takeInOnWorldThread(World world, CoopIntakeFlow.Site site,
                                                                         UUID npcUuid) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> body = world.getEntityRef(npcUuid);
        if (body == null || !body.isValid() || body.getStore() != store || !canTakeInNow(body, store)) {
            return CompletableFuture.completedFuture(CoopIntakeFlow.Result.NOT_ELIGIBLE);
        }
        long gameTimeMs = CompanionWorldTime.gameTimeMs(store);
        TameworkCompanionComponent stamp = store.getComponent(body, TameworkCompanionComponent.getComponentType());
        if (stamp != null && stamp.getProfileId() != null) {
            UUID profileId = stamp.getProfileId();
            SnapshotEnvelope envelope = snapshots.capture(body, store, profileId, stamp.getGeneration(),
                    world.getName(), gameTimeMs);
            if (envelope == null) {
                snapshotFailedAt.put(npcUuid, System.currentTimeMillis());
                return CompletableFuture.completedFuture(CoopIntakeFlow.Result.COMMIT_FAILED);
            }
            CompanionSummary summary = summaries.capture(body, store, System.currentTimeMillis());
            return flow.intakeLive(new CoopIntakeFlow.LiveIntake<>(profileId, stamp.getGeneration(), body,
                    envelope.data(), summary == null ? CompanionSummary.EMPTY : summary, site));
        }
        // The envelope's profile id only names the NPC in a failure log; no record is made.
        SnapshotEnvelope envelope = snapshots.capture(body, store, npcUuid, 0L, world.getName(), gameTimeMs);
        if (envelope == null) {
            snapshotFailedAt.put(npcUuid, System.currentTimeMillis());
            return CompletableFuture.completedFuture(CoopIntakeFlow.Result.COMMIT_FAILED);
        }
        return flow.intakeUnowned(body, CompanionSnapshots.entity(envelope), site);
    }

    /** {@link #canTakeIn} without the in-flight check, which the queued task itself holds. */
    private boolean canTakeInNow(Ref<EntityStore> body, Store<EntityStore> store) {
        TameworkCompanionComponent stamp = store.getComponent(body, TameworkCompanionComponent.getComponentType());
        if (stamp != null && stamp.getProfileId() != null) {
            CompanionRecord record = index.get(stamp.getProfileId());
            return record != null && record.location().kind() == LocationKind.LIVE
                    && record.generation() == stamp.getGeneration();
        }
        return !owned(store, body);
    }

    private boolean recentlyFailed(UUID npcUuid) {
        Long failedAt = snapshotFailedAt.get(npcUuid);
        if (failedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - failedAt < SNAPSHOT_RETRY_MS) {
            return true;
        }
        snapshotFailedAt.remove(npcUuid, failedAt);
        return false;
    }

    private static boolean owned(Store<EntityStore> store, Ref<EntityStore> body) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null ? null : store.getComponent(body, ownerType);
        return owner != null && owner.getOwnerId() != null;
    }

    @Override
    @Nonnull
    public CompletableFuture<Boolean> writeSlot(@Nonnull CoopIntakeFlow.Site site,
                                                @Nonnull TameworkCoopSlotsComponent.Slot entry) {
        Universe universe = Universe.get();
        World world = universe == null ? null : universe.getWorld(site.world());
        if (world == null || !world.isAlive()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> written = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    written.complete(writeOnWorldThread(world, site, entry));
                } catch (RuntimeException | LinkageError failure) {
                    written.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            return CompletableFuture.completedFuture(false);
        }
        // A write that runs after the timeout leaves an entry that is stale by generation.
        return written.completeOnTimeout(false, SLOT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void removeBody(@Nonnull Ref<EntityStore> body) {
        CompanionBodies.removeOnOwnWorld(body);
    }

    /**
     * Puts the entry on the coop block and marks its chunk for saving (no forced save: a crash
     * before the chunk saves leaves the record COOP without an entry, which Recover handles).
     */
    private boolean writeOnWorldThread(World world, CoopIntakeFlow.Site site, TameworkCoopSlotsComponent.Slot entry) {
        Block block = block(world, site.x(), site.y(), site.z());
        ComponentType<ChunkStore, TameworkCoopSlotsComponent> type = TameworkCoopSlotsComponent.getComponentType();
        if (block == null || type == null) {
            return false;
        }
        TameworkCoopSlotsComponent current = block.slots();
        TameworkCoopSlotsComponent.Slot existing = current == null ? null : current.get(site.slot());
        if (existing != null && CoopSlots.occupied(existing,
                existing.profileId() == null ? null : index.get(existing.profileId()),
                site.world(), site.x(), site.y(), site.z())) {
            return false;
        }
        TameworkCoopSlotsComponent base = current == null ? new TameworkCoopSlotsComponent() : current;
        block.store().putComponent(block.ref(), type, base.with(entry));
        block.info().markNeedsSaving(block.store());
        effects.play(world, site.x() + 0.5, site.y() + 0.5, site.z() + 0.5, site.coopId());
        return true;
    }

    /** The loaded block entity at the position, or null. Call on the world thread. */
    @Nullable
    private static Block block(World world, int x, int y, int z) {
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> store = chunkStore == null ? null : chunkStore.getStore();
        if (store == null) {
            return null;
        }
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null || !HytaleChunkAccess.isOwnedBy(chunk, world)) {
            return null;
        }
        Ref<ChunkStore> ref = chunk.getBlockComponentEntity(x, y, z);
        ComponentType<ChunkStore, BlockModule.BlockStateInfo> infoType = BlockModule.BlockStateInfo.getComponentType();
        if (ref == null || !ref.isValid() || infoType == null) {
            return null;
        }
        BlockModule.BlockStateInfo info = store.getComponent(ref, infoType);
        if (info == null) {
            return null;
        }
        ComponentType<ChunkStore, TameworkCoopSlotsComponent> type = TameworkCoopSlotsComponent.getComponentType();
        return new Block(store, ref, info, type == null ? null : store.getComponent(ref, type));
    }

    private record Block(Store<ChunkStore> store, Ref<ChunkStore> ref, BlockModule.BlockStateInfo info,
                         @Nullable TameworkCoopSlotsComponent slots) {
    }
}
